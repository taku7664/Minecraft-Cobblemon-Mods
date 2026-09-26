package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.morebattlecontent.leaguechallenge.server.LeagueTerminal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.components.Button
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
        if (System.getenv("MBC_LEAGUE_CAPTURE_LIVE") != "1") return
        val logger = LoggerFactory.getLogger("league-live-smoke")
        logger.info("Enabled disposable-world League UI smoke test")
        val placed = AtomicBoolean(false)
        val captured = AtomicBoolean(false)
        var pos: BlockPos? = null
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        var warningAccepted = false
        var revision = -1L
        var waitingScreen: String? = null
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
            when (phase) {
                0 -> {
                    if (client.screen != null) return@EndTick
                    client.options.guiScale().set(2)
                    client.resizeDisplay()
                    server.execute {
                        val serverPlayer = checkNotNull(server.playerList.getPlayer(player.uuid))
                        // The first client world tick can precede its initial position packet.
                        pos = (0..2).asSequence().flatMap { dy ->
                            Direction.Plane.HORIZONTAL.asSequence().map { serverPlayer.blockPosition().relative(it).above(dy) }
                        }.firstOrNull { serverPlayer.serverLevel().isEmptyBlock(it) }
                            ?: error("No empty nearby position for the smoke terminal")
                        serverPlayer.serverLevel().setBlockAndUpdate(pos!!, LeagueTerminal.block.defaultBlockState())
                        placed.set(true)
                    }
                    phase = 1
                }
                1 -> if (placed.get() && level.getBlockState(pos!!).`is`(LeagueTerminal.block)) {
                    check(player.mainHandItem.isEmpty) { "Smoke interaction requires an empty hand" }
                    client.gameMode!!.useItemOn(player, InteractionHand.MAIN_HAND,
                        BlockHitResult(Vec3.atCenterOf(pos!!), Direction.UP, pos!!, false))
                    logger.info("Sent actual terminal use-item interaction at {}", pos)
                    phase = 2; phaseTick = ticks
                }
                2 -> if (client.screen is LeagueHomeScreen && ticks > phaseTick + 20) {
                    val view = checkNotNull(LeagueClientSession.current)
                    revision = view.revision
                    logger.info("Production home received nonce={} badges={} cap={} error={}", view.nonce, view.badges, view.cap, view.errorKey)
                    Screenshot.grab(client.gameDirectory, "league-live-top.png", client.mainRenderTarget) { captured.set(true) }
                    phase = 3
                }
                3 -> if (captured.get()) {
                    val screen = client.screen as LeagueHomeScreen
                    check(screen.keyPressed(GLFW.GLFW_KEY_END, 0, 0)) { "List did not scroll" }
                    screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0)
                    check(screen.focused != null) { "Keyboard focus missing" }
                    captured.set(false)
                    phase = 4; phaseTick = ticks
                }
                4 -> if (ticks > phaseTick + 3) {
                    Screenshot.grab(client.gameDirectory, "league-live-scrolled.png", client.mainRenderTarget) { captured.set(true) }
                    phase = 5
                }
                5 -> if (captured.get()) {
                    val screen = client.screen as LeagueHomeScreen
                    val refresh = screen.children().filterIsInstance<CobblemonUiButton>()
                        .single { it.message.string == LeagueHomeScreen.copy("refresh").string }
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
                    check(client.screen !is LeagueHomeScreen) { "Background state reopened dismissed home" }
                    logger.info("PASS terminal packet -> production home -> scroll/focus -> server refresh -> close; progression unchanged")
                    client.stop()
                    phase = 8
                }
            }
        })
    }
}
