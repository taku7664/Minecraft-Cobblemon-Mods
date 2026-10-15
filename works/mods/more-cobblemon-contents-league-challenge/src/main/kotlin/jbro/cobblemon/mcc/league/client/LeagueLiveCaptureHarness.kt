package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.mcc.league.server.LeagueTerminal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.network.chat.Component
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in integration smoke test. Never runs in release or outside its disposable copied world. */
internal object LeagueLiveCaptureHarness {
    fun installFromEnvironment() {
        if (System.getenv("MCC_LEAGUE_CAPTURE_LIVE") != "1") return
        val logger = LoggerFactory.getLogger("league-live-smoke")
        logger.info("Enabled disposable-world League UI smoke test")
        val placed = AtomicBoolean(false)
        val captured = AtomicBoolean(false)
        val captureId = System.currentTimeMillis()
        val galar = System.getenv("MCC_LEAGUE_CAPTURE_GALAR") == "1"
        var pos: BlockPos? = null
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        var warningAccepted = false
        var revision = -1L
        var waitingScreen: String? = null
        var tutorialCleared = false
        val cappedBattleStarted = AtomicBoolean(false)
        val cappedBattleChecked = AtomicBoolean(false)
        var originalParty = emptyList<String>()
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            ticks++
            if (ticks == 1) client.options.pauseOnLostFocus = false
            check(ticks < 3600) { "League live smoke timed out in phase $phase" }
            if (client.screen?.javaClass?.name != waitingScreen) {
                waitingScreen = client.screen?.javaClass?.name
                logger.info("Smoke phase={} screen={}", phase, waitingScreen)
            }
            (client.screen as? AccessibilityOnboardingScreen)?.let {
                it.onClose()
                return@EndTick
            }
            // Only this explicit copied-world harness accepts the new-datapack backup prompt.
            (client.screen as? BackupConfirmScreen)?.let { screen ->
                val backup = screen.children().filterIsInstance<Button>().single {
                    it.message.string == Component.translatable("selectWorld.backupJoinConfirmButton").string
                }
                backup.onPress()
                return@EndTick
            }
            val warning = client.screen as? SnapshotWarningScreen
            if (warning != null && !warningAccepted) {
                warningAccepted = true
                warning.consumer(SnapshotWarningScreen.Acknowledgement.YES, false)
                return@EndTick
            }
            val server = client.singleplayerServer ?: return@EndTick
            val player = client.player ?: return@EndTick
            val level = client.level ?: return@EndTick
            if (client.overlay != null) return@EndTick
            check(server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() == "league-wiring-smoke") {
                "Live capture may modify only the copied league-wiring-smoke world"
            }
            // Terminals open the hub dashboard; select the embedded League tab through its public rail action.
            if (phase == 2) (client.screen as? jbro.cobblemon.mcc.client.hub.MccHubScreen)?.let { hub ->
                if (hub.selectedTabId != LeagueHomeController.CONTENT) hub.selectTab(LeagueHomeController.CONTENT)
            }
            when (phase) {
                0 -> {
                    if (client.screen != null) return@EndTick
                    client.options.guiScale().set(System.getenv("MCC_LEAGUE_CAPTURE_GUI_SCALE")?.toIntOrNull() ?: 2)
                    client.resizeDisplay()
                    server.execute {
                        val serverPlayer = checkNotNull(server.playerList.getPlayer(player.uuid))
                        if (galar) {
                            // This branch is still behind the disposable-world check above. Never touches a user's save.
                            val primary = checkNotNull(jbro.cobblemon.mcc.league.server.LeagueCatalogResources.current)
                            val storage = jbro.cobblemon.mcc.league.server.LeagueSavedData.get(server)
                            storage.write(primary.id, player.uuid, jbro.cobblemon.mcc.league.system.LeagueProgress(
                                cleared = (primary.gyms + primary.finals).toSet(), champion = true, championAt = System.currentTimeMillis()))
                            jbro.cobblemon.mcc.league.server.LeagueIntegrations.syncCap(serverPlayer, 100)
                            jbro.cobblemon.mcc.league.server.LeagueIntegrations.syncCap(serverPlayer, 80)
                            check(jbro.cobblemon.mcc.league.server.LeagueIntegrations.currentCap(serverPlayer) == 100)
                            check(jbro.cobblemon.mcc.league.server.LeagueIntegrations.currentCap(serverPlayer, true) == 100)
                            logger.info("PASS live global cap: growth=100 catching=100 after entitlement=80")
                        }
                        // The first client world tick can precede its initial position packet.
                        pos = (0..2).asSequence().flatMap { dy ->
                            Direction.Plane.HORIZONTAL.asSequence().map { serverPlayer.blockPosition().relative(it).above(dy) }
                        }.firstOrNull { serverPlayer.serverLevel().isEmptyBlock(it) }
                            ?: error("No empty nearby position for the smoke terminal")
                        serverPlayer.serverLevel().setBlockAndUpdate(pos!!, LeagueTerminal.terminal.block.defaultBlockState())
                        placed.set(true)
                    }
                    phase = 1
                }
                1 -> if (placed.get() && level.getBlockState(pos!!).`is`(LeagueTerminal.terminal.block)) {
                    check(player.mainHandItem.isEmpty) { "Smoke interaction requires an empty hand" }
                    client.gameMode!!.useItemOn(player, InteractionHand.MAIN_HAND,
                        BlockHitResult(Vec3.atCenterOf(pos!!), Direction.UP, pos!!, false))
                    logger.info("Sent actual terminal use-item interaction at {}", pos)
                    phase = 2; phaseTick = ticks
                }
                // Development screenshots capture the real screen without first-join tutorial overlays.
                2 -> if (leagueTabShown(client) && LeagueHomeController.state.view != null && ticks > phaseTick + 20) {
                    if (!tutorialCleared) {
                        client.toasts.clear()
                        GLFW.glfwSetCursorPos(client.window.window, 0.0, 0.0)
                        tutorialCleared = true
                        phaseTick = ticks
                        return@EndTick
                    }
                    if (ticks <= phaseTick + 4) return@EndTick
                    val view = checkNotNull(LeagueClientSession.current)
                    revision = view.revision
                    logger.info("Production home received gui={}x{} nonce={} badges={} cap={} error={}",
                        client.window.guiScaledWidth, client.window.guiScaledHeight, view.nonce, view.badges, view.cap, view.errorKey)
                    Screenshot.grab(client.gameDirectory, "league-live-$captureId-top.png", client.mainRenderTarget) { captured.set(true) }
                    phase = if (galar) 20 else 3
                }
                20 -> if (captured.get()) {
                    val screen = checkNotNull(client.screen)
                    val view = checkNotNull(LeagueClientSession.current)
                    val region = screen.children().filterIsInstance<CobblemonUiButton>()
                        .single { it.message.string == Component.translatable(view.nameKey).string }
                    check(screen.mouseClicked(region.x + region.width / 2.0, region.y + region.height / 2.0, 0))
                    phase = 21; phaseTick = ticks
                }
                21 -> if (!LeagueHomeController.state.pending && ticks > phaseTick + 10) {
                    val view = checkNotNull(LeagueClientSession.current)
                    check(view.leagueId.endsWith(":galar") && view.leagueUnlocked) { "Region selection failed: ${view.leagueId}" }
                    check(view.challenges.size == 11 && view.challenges.count { it.stage == 4 } == 2)
                    check(view.challenges.first().battleCap == 22 && view.cap >= 80)
                    check(view.challenges.first().mechanicFlags == jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.DYNAMAX)
                    revision = view.revision
                    captured.set(false)
                    Screenshot.grab(client.gameDirectory, "league-live-$captureId-galar.png", client.mainRenderTarget) { captured.set(true) }
                    logger.info("PASS Galar selection: regions={} branches={} battleCap={} worldCap={}", view.regions.size, view.challenges.size, view.challenges.first().battleCap, view.cap)
                    phase = 22
                }
                22 -> if (captured.get()) {
                    GLFW.glfwSetWindowSize(client.window.window, 640, 480)
                    client.resizeDisplay()
                    phase = 23; phaseTick = ticks
                }
                23 -> if (ticks > phaseTick + 10) {
                    check(client.window.guiScaledWidth == 320 && client.window.guiScaledHeight == 240) {
                        "Compact GUI was ${client.window.guiScaledWidth}x${client.window.guiScaledHeight}"
                    }
                    captured.set(false)
                    Screenshot.grab(client.gameDirectory, "league-live-$captureId-galar-compact.png", client.mainRenderTarget) { captured.set(true) }
                    logger.info("PASS compact GUI 320x240")
                    phase = 24
                }
                24 -> if (captured.get()) {
                    val selection = LeagueHomeController.state.selectedId
                    val screen = checkNotNull(client.screen)
                    var moved = 0
                    repeat(12) {
                        screen.children().filterIsInstance<CobblemonUiButton>().single { it.message.string == ">" }
                            .takeIf { it.active }?.let { arrow ->
                                check(screen.mouseClicked(arrow.x + arrow.width / 2.0, arrow.y + arrow.height / 2.0, 0)); moved++
                            }
                    }
                    check(moved > 0)
                    check(screen.children().filterIsInstance<AbstractButton>().any {
                        it.message.string == Component.translatable(checkNotNull(LeagueClientSession.current).challenges.last().nameKey).string
                    }) { "Last route stage is unreachable" }
                    check(LeagueHomeController.state.selectedId == selection)
                    check(LeagueClientSession.current?.revision == revision)
                    phase = 25; phaseTick = ticks
                }
                25 -> if (ticks > phaseTick + 3) {
                    captured.set(false)
                    Screenshot.grab(client.gameDirectory, "league-live-$captureId-route-end.png", client.mainRenderTarget) { captured.set(true) }
                    phase = 26
                }
                26 -> if (captured.get()) {
                    val screen = checkNotNull(client.screen)
                    repeat(12) {
                        screen.children().filterIsInstance<CobblemonUiButton>().single { it.message.string == "<" }
                            .takeIf { it.active }?.let { arrow ->
                                check(screen.mouseClicked(arrow.x + arrow.width / 2.0, arrow.y + arrow.height / 2.0, 0))
                            }
                    }
                    logger.info("PASS route viewport: last stage reached and returned without changing selection or progress")
                    phase = 3
                }
                3 -> if (captured.get()) {
                    val screen = checkNotNull(client.screen)
                    val second = checkNotNull(LeagueClientSession.current).challenges[1]
                    val route = screen.children().filterIsInstance<AbstractButton>().single {
                        it.message.string == Component.translatable(second.nameKey).string
                    }
                    check(screen.mouseClicked(route.x + route.width / 2.0, route.y + route.height / 2.0, 0)) {
                        "Route selection did not click"
                    }
                    check(LeagueHomeController.state.selectedId == second.id) { "Route selection did not update" }
                    screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0)
                    check(screen.focused != null) { "Keyboard focus missing" }
                    captured.set(false)
                    phase = 4; phaseTick = ticks
                }
                4 -> if (ticks > phaseTick + 3) {
                    Screenshot.grab(client.gameDirectory, "league-live-$captureId-selected.png", client.mainRenderTarget) { captured.set(true) }
                    phase = 5
                }
                5 -> if (captured.get()) {
                    val screen = checkNotNull(client.screen)
                    val refresh = screen.children().filterIsInstance<CobblemonUiButton>()
                        .single { it.message.string == leagueCopy("refresh").string }
                    check(screen.mouseClicked(refresh.x + refresh.width / 2.0, refresh.y + refresh.height / 2.0, 0))
                    check(LeagueHomeController.state.pending)
                    phase = 6
                }
                6 -> if (!LeagueHomeController.state.pending) {
                    check(LeagueClientSession.current!!.revision == revision) { "UI smoke changed progression" }
                    client.screen!!.onClose()
                    check(client.screen == null)
                    phase = 7; phaseTick = ticks
                }
                7 -> if (ticks > phaseTick + 10) {
                    check(!leagueTabShown(client)) { "Background state reopened dismissed home" }
                    logger.info("PASS terminal packet -> hub League tab -> route selection/focus -> server refresh -> close; progression unchanged")
                    if (!galar) { client.stop(); phase = 8 }
                    else {
                        server.execute {
                            val sp = checkNotNull(server.playerList.getPlayer(player.uuid))
                            val party = com.cobblemon.mod.common.Cobblemon.storage.getParty(sp)
                            party.clearParty()
                            listOf("mewtwo level=100 moves=psychic", "magikarp level=10 moves=splash").forEach {
                                party.add(com.cobblemon.mod.common.api.pokemon.PokemonProperties.parse(it).create())
                            }
                            val catalog = checkNotNull(jbro.cobblemon.mcc.league.server.LeagueCatalogResources.find("more_cobblemon_contents_league_challenge:galar"))
                            val milo = catalog.challenges.getValue(catalog.gyms.first())
                            originalParty = jbro.cobblemon.mcc.api.battle.ManagedPveBattles.snapshotParty(sp, 100)
                            val request = jbro.cobblemon.mcc.api.battle.ManagedPveBattles.Request(java.util.UUID.randomUUID(),
                                LeagueHomeController.CONTENT, milo.id, milo.nameKey, originalParty, milo.team,
                                mechanicFlags = jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.DYNAMAX,
                                appearance = jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin(checkNotNull(milo.skin), false), stage = "gym")
                            checkNotNull(jbro.cobblemon.mcc.api.battle.ManagedPveBattles.start(sp, request, 22) {})
                            cappedBattleStarted.set(true)
                        }
                        phase = 30; phaseTick = ticks
                    }
                }
                30 -> if (cappedBattleStarted.get() && com.cobblemon.mod.common.client.CobblemonClient.battle != null && ticks > phaseTick + 20) {
                    server.execute {
                        val sp = checkNotNull(server.playerList.getPlayer(player.uuid))
                        val battle = checkNotNull(com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(sp))
                        val levels = battle.actors.filter { it.uuid == sp.uuid }.flatMap { it.pokemonList }.map { it.effectedPokemon.level }
                        check(levels == listOf(22, 10)) { "Wrong materialized levels: $levels" }
                        val party = com.cobblemon.mod.common.Cobblemon.storage.getParty(sp)
                        check(party.map { it.saveToJSON(sp.registryAccess()).toString() } == originalParty) { "Original party changed during clone battle" }
                        logger.info("PASS live cap: original=[100,10] battle={} original JSON unchanged", levels)
                        jbro.cobblemon.mcc.api.battle.ManagedPveBattles.cancel(server, sp.uuid)
                        cappedBattleChecked.set(true)
                    }
                    phase = 31
                }
                31 -> if (cappedBattleChecked.get() && com.cobblemon.mod.common.client.CobblemonClient.battle == null) {
                    client.stop(); phase = 8
                }
            }
        })
    }
}

/** The terminal lands on the hub's League tab rather than a League screen of its own. */
private fun leagueTabShown(client: net.minecraft.client.Minecraft): Boolean {
    val hub = jbro.cobblemon.mcc.client.hub.MccHubScreen.current ?: return false
    return client.screen === hub && hub.selectedTabId == LeagueHomeController.CONTENT
}
