package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.MccBattleHubClientState
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Screenshot
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
                // Content mods register their own tabs during client init, which has finished by now.
                registerPreviewTabs()
                MccBattleHubClientState.update(if (fixture == "empty") 0 else 1_284)
                MccBattleHubClientState.dashboard = records
                client.setScreen(MccHubScreen())
                opened = true
                logger.info("Opened hub capture fixture={} locale={}", fixture, client.languageManager.selected)
                return@EndTick
            }
            if (closed) return@EndTick
            ticks += 1
            if (!requested && ticks >= 20) {
                requested = true
                val name = "mcc-hub-$fixture-${client.languageManager.selected}-" +
                    "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
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
                        "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
                    Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                        logger.info("Hub content capture {} ({}): {}", name, screen?.javaClass?.simpleName, result.string)
                        contentCaptured.set(true)
                    }
                }
                if (contentCaptured.get()) {
                    closed = true
                    client.stop()
                }
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
                        listOf("single_level_50", "single_open_level", "double_level_50", "double_open_level")
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
