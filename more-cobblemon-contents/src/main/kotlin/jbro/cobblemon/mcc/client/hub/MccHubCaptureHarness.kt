package jbro.cobblemon.mcc.client.hub

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.MccBattleHubClientState
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.internal.hub.BattleHubDashboardPayload
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Development-only visual check of the hub. Set `MCC_HUB_CAPTURE` to a fixture (`empty`, `standard`,
 * `dense`); optionally `MCC_HUB_CAPTURE_LOCALE` (default `ko_kr`, or `en_us`) and `MCC_HUB_CAPTURE_GUI_SCALE` (1-4).
 * Launch with `--quickPlaySingleplayer <world>`: once the world is loaded the hub opens with fixture data,
 * is captured to `screenshots/`, closed through its ESC path, and the client stops.
 * With `MCC_HUB_CAPTURE_OPEN=<tab id>` the hub then selects that tab as its rail button would: an embedded tab
 * is captured inside the hub once its server state had time to arrive, a screen tab once the server opened it.
 * `MCC_HUB_CAPTURE_PRESS=<translation key,...>` then presses the tab's buttons with those labels one by one, capturing
 * after each, to reach later phases of a tab.
 * `MCC_HUB_CAPTURE_PARTY=<species,...>` first tops the player's party up to that many Pokemon through the integrated
 * server, so party-driven tabs show real portraits.
 * `MCC_HUB_CAPTURE_THEME=<style.palette,...>` (for example `ds_window.tower_lobby,pixel_frame.factory_night`) draws
 * the hub in the first theme, names the captures after it, and
 * once the last step is captured redraws the tab in each further theme and captures it again, all in one launch.
 * `MCC_HUB_CAPTURE_PERF=<frames>` then, with the frame cap and vsync off, times that many frames of the open hub and
 * logs how long the hub took to draw and how long each frame took, before closing it.
 */
object MccHubCaptureHarness {
    private val logger = MoreCobblemonContents.LOGGER

    fun installFromEnvironment() {
        val fixture = System.getenv("MCC_HUB_CAPTURE")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return
        val records = FIXTURES[fixture] ?: run {
            logger.error("Ignoring hub capture with unknown fixture {}", fixture)
            return
        }
        // Korean is the working locale; set MCC_HUB_CAPTURE_LOCALE=en_us to check English layouts.
        val locale = System.getenv("MCC_HUB_CAPTURE_LOCALE")?.trim()?.takeIf { it.isNotEmpty() } ?: "ko_kr"
        val guiScale = System.getenv("MCC_HUB_CAPTURE_GUI_SCALE")?.trim()?.toIntOrNull()?.takeIf { it in 1..4 }
        val openContent = System.getenv("MCC_HUB_CAPTURE_OPEN")?.trim()?.takeIf { it.isNotEmpty() }
        val themes = ArrayDeque(System.getenv("MCC_HUB_CAPTURE_THEME")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
        themes.firstOrNull()?.let { id -> check(CobblemonUiSharedTheme.select(id)) { "Unknown hub capture theme $id" } }
        themes.removeFirstOrNull()
        var themeTicks = 0
        val themeCaptured = AtomicBoolean(true)
        val partyFixture = System.getenv("MCC_HUB_CAPTURE_PARTY")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        var partyRequested = partyFixture.isEmpty()
        val presses = ArrayDeque(System.getenv("MCC_HUB_CAPTURE_PRESS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
        var pressStep = 0
        var pressTicks = 0
        val pressCaptured = AtomicBoolean(true)
        var partyWait = 0

        val namedThemes = System.getenv("MCC_HUB_CAPTURE_THEME") != null
        val perfFrames = System.getenv("MCC_HUB_CAPTURE_PERF")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val perf = perfFrames?.let(::HubPerf)
        var perfTicks = 0
        fun themeSuffix() = if (namedThemes) "-${CobblemonUiSharedTheme.id}" else ""
        var guiScaleApplied = guiScale == null
        val languageReady = AtomicBoolean(false)
        val languageFailure = AtomicReference<Throwable?>()
        var languageRequested = false
        var opened = false
        var requested = false
        var closed = false
        val captured = AtomicBoolean(false)
        var ticks = 0
        var contentRequested = false
        var contentTicks = 0
        var contentCaptureRequested = false
        val contentCaptured = AtomicBoolean(false)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            // The capture worlds use experimental settings, so loading one stops at the backup warning; press
            // through it as a player would, instead of waiting for someone at the window.
            (client.screen as? BackupConfirmScreen)?.let { warning ->
                val skip = Component.translatable("selectWorld.backupJoinSkipButton").string
                val button = warning.children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string == skip }
                if (button != null) {
                    button.onPress()
                    logger.info("Hub capture skipped the experimental world warning")
                }
                return@EndTick
            }
            if (!guiScaleApplied) {
                client.options.guiScale().set(checkNotNull(guiScale))
                client.resizeDisplay()
                guiScaleApplied = true
                return@EndTick
            }
            languageFailure.get()?.let { throw IllegalStateException("Hub capture language reload failed", it) }
            if (!languageReady.get()) {
                if (!languageRequested && client.overlay == null && (client.screen != null || client.level != null)) {
                    client.options.languageCode = locale
                    client.languageManager.setSelected(locale)
                    languageRequested = true
                    client.reloadResourcePacks().whenComplete { _, error ->
                        if (error == null) languageReady.set(true) else languageFailure.set(error)
                    }
                }
                return@EndTick
            }
            if (!opened) {
                // The dashboard draws the real player entity, so the hub opens only inside a loaded world.
                if (client.level == null || client.player == null || client.screen != null || client.overlay != null) return@EndTick
                if (!partyRequested) {
                    val server = checkNotNull(client.singleplayerServer) { "The party fixture needs a singleplayer world" }
                    val name = checkNotNull(client.player).gameProfile.name
                    val have = CobblemonClient.storage.party.count { it != null }
                    partyFixture.drop(have).forEach { species ->
                        server.execute {
                            server.commands.performPrefixedCommand(server.createCommandSourceStack(), "givepokemonother $name $species level=50")
                        }
                    }
                    logger.info("Hub capture party had {} Pokemon; gave {}", have, partyFixture.drop(have))
                    partyRequested = true
                    return@EndTick
                }
                // The party sync reaches the client a few ticks after the server gives the Pokemon.
                if (partyFixture.isNotEmpty() && partyWait++ < 40) return@EndTick
                // Content mods register their own tabs during client init, which has finished by now.
                registerPreviewTabs()
                MccBattleHubClientState.update(if (fixture == "empty") 0 else 1_284)
                MccBattleHubClientState.dashboard = BattleHubDashboardPayload(records.sumOf { it.battles }, records.sumOf { it.wins },
                    (if (records.isEmpty()) emptyList() else listOf(SAMPLE_LEAGUE_CARD)) +
                        records.map { it.contentId }.distinct().mapNotNull { id -> MccDashboardCards.records(id, records.filter { it.contentId == id }) })
                client.setScreen(MccHubScreen())
                opened = true
                logger.info("Opened hub capture fixture={} locale={}", fixture, client.languageManager.selected)
                return@EndTick
            }
            if (closed) return@EndTick
            ticks += 1
            // Tutorial and advancement toasts would cover the tab being checked.
            client.toasts.clear()
            if (!requested && ticks >= 20) {
                requested = true
                val name = "mcc-hub-$fixture-${client.languageManager.selected}-" +
                    "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}${themeSuffix()}.png"
                Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                    logger.info("Hub capture {}: {}", name, result.string)
                    captured.set(true)
                }
            }
            if (requested && captured.get() && openContent != null) {
                val embedded = when (checkNotNull(MccHubTabs.get(openContent)) { "Unknown hub tab $openContent" }.kind) {
                    is MccHubTabKind.Embedded -> true
                    is MccHubTabKind.Screen -> false
                }
                if (!contentRequested) {
                    // The server opens only the tabs of a hub it opened; this hub was opened on the client.
                    client.singleplayerServer?.let { server ->
                        val playerId = checkNotNull(client.player).uuid
                        server.execute { server.playerList.getPlayer(playerId)?.let { BattleHubNetworking.openForCapture(it, listOf(openContent)) } }
                    }
                    checkNotNull(client.screen as? MccHubScreen) { "Hub closed before selecting $openContent" }.selectTab(openContent)
                    contentRequested = true
                    logger.info("Selected hub tab {}", openContent)
                    return@EndTick
                }
                val screen = client.screen
                val ready = if (embedded) screen is MccHubScreen && screen.selectedTabId == openContent
                    else screen != null && screen !is MccHubScreen
                if (!ready) {
                    if (ticks >= 400) error("Hub tab $openContent did not show")
                    return@EndTick
                }
                contentTicks += 1
                // Embedded tabs wait longer: their server state arrives after the tab is shown.
                if (!contentCaptureRequested && contentTicks >= if (embedded) 40 else 20) {
                    contentCaptureRequested = true
                    val name = "mcc-hub-open-${openContent.substringAfter(':')}-${client.languageManager.selected}-" +
                        "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}${themeSuffix()}.png"
                    Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                        logger.info("Hub content capture {} ({}): {}", name, screen?.javaClass?.simpleName, result.string)
                        contentCaptured.set(true)
                    }
                }
                if (!contentCaptured.get() || !pressCaptured.get()) return@EndTick
                if (pressTicks > 0) {
                    // Give the server's answer to the press time to arrive and the tab time to rebuild.
                    if (--pressTicks == 0) {
                        pressCaptured.set(false)
                        val name = "mcc-hub-open-${openContent.substringAfter(':')}-step$pressStep-${client.languageManager.selected}-" +
                            "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}${themeSuffix()}.png"
                        Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                            logger.info("Hub step capture {}: {}", name, result.string)
                            pressCaptured.set(true)
                        }
                    }
                    return@EndTick
                }
                val key = presses.removeFirstOrNull()
                if (key == null) {
                    if (!themeCaptured.get()) return@EndTick
                    if (themeTicks > 0) {
                        // A few ticks for the rebuilt widgets and their models to draw in the new theme.
                        if (--themeTicks == 0) {
                            themeCaptured.set(false)
                            val name = "mcc-hub-open-${openContent.substringAfter(':')}-${client.languageManager.selected}-" +
                                "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}${themeSuffix()}.png"
                            Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                                logger.info("Hub theme capture {}: {}", name, result.string)
                                themeCaptured.set(true)
                            }
                        }
                        return@EndTick
                    }
                    val theme = themes.removeFirstOrNull()
                    if (theme != null) {
                        // Selecting restyles the open hub's installed theme at once.
                        check(CobblemonUiSharedTheme.select(theme)) { "Unknown hub capture theme $theme" }
                        checkNotNull(client.screen as? MccHubScreen) { "Hub closed before the $theme capture" }.rebuild()
                        themeTicks = 10
                        return@EndTick
                    }
                    closed = true
                    client.stop()
                    return@EndTick
                }
                val label = Component.translatable(key).string
                val target = checkNotNull(client.screen).children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string == label }
                checkNotNull(target) { "No hub button labelled $label ($key)" }
                check(target.active) { "Hub button $label is disabled" }
                target.onPress()
                pressStep += 1
                pressTicks = 40
                logger.info("Pressed hub button {}", label)
                return@EndTick
            }
            if (requested && captured.get() && perf != null && !perf.done) {
                if (perfTicks++ == 0) {
                    client.options.framerateLimit().set(260)
                    client.options.enableVsync().set(false)
                    perf.hook(checkNotNull(client.screen as? MccHubScreen) { "Hub closed before timing it" })
                }
                // A second for the frame rate to settle after the cap is lifted.
                if (perfTicks == 20) perf.start()
                if (perf.full) perf.report()
                if (perfTicks >= 2400) error("Hub timing did not collect ${perf.frames} frames")
                return@EndTick
            }
            if (requested && captured.get()) {
                val screen = checkNotNull(client.screen)
                screen.onClose()
                check(client.screen !== screen) { "Hub did not close through onClose" }
                logger.info("Verified hub ESC close path")
                closed = true
                client.stop()
            } else if (ticks >= 200) {
                error("Hub capture timed out")
            }
        })
    }

    /** Times [frames] frames of one hub: the hub's own drawing, and the whole frame from one hub draw to the next. */
    private class HubPerf(val frames: Int) {
        private val draw = LongArray(frames)
        private val frame = LongArray(frames)
        private var count = 0
        private var recording = false
        private var drawStart = 0L
        private var lastEnd = 0L
        var done = false
            private set
        val full get() = count >= frames

        fun hook(screen: MccHubScreen) {
            ScreenEvents.beforeRender(screen).register { _, _, _, _, _ -> drawStart = System.nanoTime() }
            ScreenEvents.afterRender(screen).register { _, _, _, _, _ ->
                val now = System.nanoTime()
                if (recording && count < frames && lastEnd != 0L) {
                    draw[count] = now - drawStart
                    frame[count] = now - lastEnd
                    count += 1
                }
                lastEnd = now
            }
        }

        fun start() {
            recording = true
        }

        fun report() {
            fun ms(values: LongArray, at: Double) = values.sorted()[((values.size - 1) * at).toInt()] / 1_000_000.0
            logger.info("Hub timing over {} frames: draw median {} ms p90 {} ms, frame median {} ms p90 {} ms ({} fps)",
                frames, "%.3f".format(ms(draw, 0.5)), "%.3f".format(ms(draw, 0.9)), "%.3f".format(ms(frame, 0.5)),
                "%.3f".format(ms(frame, 0.9)), "%.1f".format(1000.0 / ms(frame, 0.5)))
            done = true
        }
    }

    /** Shows the content tabs in the rail even when the capture runs without the content mods and their names. */
    private fun registerPreviewTabs() {
        listOf(
            ManagedBattleContentIds.LEAGUE_CHALLENGE to 90,
            ManagedBattleContentIds.BATTLE_TOWER to 100,
            ManagedBattleContentIds.BATTLE_FACTORY to 110,
            ManagedBattleContentIds.PVP to 120,
        ).filter { (id, _) -> MccHubTabs.get(id) == null }.forEach { (id, order) ->
            MccHubTabs.register(MccHubTab(id, Component.translatableWithFallback(MccDashboardPresentation.contentNameKey(id), id.substringAfter(':')), order,
                MccHubTabKind.Screen {}))
        }
    }

    /** What a League section's card looks like, since the capture runs without the League's server side. */
    private val SAMPLE_LEAGUE_CARD = jbro.cobblemon.mcc.api.hub.MccDashboardCard(
        ManagedBattleContentIds.LEAGUE_CHALLENGE, MccDashboardCards.contentName(ManagedBattleContentIds.LEAGUE_CHALLENGE),
        listOf(
            jbro.cobblemon.mcc.api.hub.MccDashboardStat(Component.literal("배지"), Component.literal("5/8")),
            jbro.cobblemon.mcc.api.hub.MccDashboardStat(Component.literal("레벨캡"), Component.literal("43")),
            jbro.cobblemon.mcc.api.hub.MccDashboardStat(Component.literal("등급"), Component.literal("하이퍼볼")),
        ),
        listOf(
            jbro.cobblemon.mcc.api.hub.MccDashboardRow(Component.literal("다음 도전"), Component.literal("동관")),
            jbro.cobblemon.mcc.api.hub.MccDashboardRow(Component.literal("하드 리그"), Component.literal("챔피언이 되면 열림")),
            jbro.cobblemon.mcc.api.hub.MccDashboardRow(Component.literal("야생 트레이너"), Component.literal("12승 3패"), Component.literal("연승 4 · 최고 연승 7")),
        ),
    )

    private val FIXTURES: Map<String, List<BattleHubRecordView>> = mapOf(
        "empty" to emptyList(),
        "standard" to listOf(
            BattleHubRecordView(ManagedBattleContentIds.BATTLE_TOWER, "single", 42, 9, 6, 21),
            BattleHubRecordView(ManagedBattleContentIds.BATTLE_TOWER, "double", 7, 4, 0, 5),
            BattleHubRecordView(ManagedBattleContentIds.BATTLE_FACTORY, "single_level_50", 18, 3, 4, 11,
                mapOf("highest_floor" to 3)),
            BattleHubRecordView(ManagedBattleContentIds.PVP, "single", 12, 10, 2, 4),
        ),
        "dense" to buildList {
            listOf(ManagedBattleContentIds.BATTLE_TOWER, ManagedBattleContentIds.BATTLE_FACTORY, ManagedBattleContentIds.PVP)
                .forEach { content ->
                    val formats = if (content == ManagedBattleContentIds.BATTLE_FACTORY) {
                        listOf("single_level_50", "single_open_level")
                    } else {
                        listOf("single", "double")
                    }
                    formats.forEachIndexed { index, format ->
                        add(BattleHubRecordView(content, format, 1_200L + index * 37, 480L + index * 11, 3 + index, 88 + index,
                            if (content == ManagedBattleContentIds.BATTLE_FACTORY) mapOf("highest_floor" to 7L + index) else emptyMap()))
                    }
                }
        },
    )
}
