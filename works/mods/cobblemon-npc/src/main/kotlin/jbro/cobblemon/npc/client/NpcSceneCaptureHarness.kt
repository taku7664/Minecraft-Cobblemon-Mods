package jbro.cobblemon.npc.client

import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.server.DialogueSessions
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

/**
 * Development-only check of the camera on a talking NPC. Set `NPC_SCENE_CAPTURE=1` and launch with
 * `--quickPlaySingleplayer npc-capture` (a disposable world): it stands an NPC in Cynthia's skin in front of the
 * player, opens the bundled `tower_guide` talk from it, captures the box with the camera on the NPC, closes it and
 * captures the view handed back. Screenshots land in `screenshots/npc-scene-<step>.png`.
 */
internal object NpcSceneCaptureHarness {
    private const val WORLD = "npc-capture"

    fun installFromEnvironment() {
        if (System.getenv("NPC_SCENE_CAPTURE") != "1" || !FabricLoader.getInstance().isDevelopmentEnvironment) return
        val logger = LoggerFactory.getLogger("npc-scene-capture")
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            ticks++
            if (ticks == 1) client.options.pauseOnLostFocus = false
            check(ticks < 4000) { "NPC scene capture timed out in phase $phase" }
            (client.screen as? AccessibilityOnboardingScreen)?.let { it.onClose(); return@EndTick }
            (client.screen as? BackupConfirmScreen)?.let { screen ->
                screen.children().filterIsInstance<Button>().single {
                    it.message.string == Component.translatable("selectWorld.backupJoinConfirmButton").string
                }.onPress()
                return@EndTick
            }
            (client.screen as? SnapshotWarningScreen)?.let {
                it.consumer(SnapshotWarningScreen.Acknowledgement.YES, false)
                return@EndTick
            }
            val server = client.singleplayerServer ?: return@EndTick
            val player = client.player ?: return@EndTick
            if (client.level == null || client.overlay != null) return@EndTick
            check(server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() == WORLD) {
                "The NPC capture may only change the disposable $WORLD world"
            }
            fun since(n: Int) = ticks - phaseTick >= n
            fun next(to: Int) { phase = to; phaseTick = ticks }
            fun shot(step: String) {
                Screenshot.grab(client.gameDirectory, "npc-scene-$step.png", client.mainRenderTarget) {}
                logger.info("NPC CAPTURE {} (screen={})", step, client.screen?.javaClass?.simpleName)
            }
            when (phase) {
                0 -> if (client.screen == null && since(40)) {
                    client.options.guiScale().set(2)
                    client.resizeDisplay()
                    server.execute {
                        val serverPlayer = server.playerList.getPlayer(player.uuid)!!
                        val level = serverPlayer.serverLevel()
                        val look = serverPlayer.lookAngle.multiply(1.0, 0.0, 1.0).normalize()
                        val npc = CobblemonNpc.NPC.create(level)!!
                        npc.moveTo(serverPlayer.x + look.x * 3.0, serverPlayer.y, serverPlayer.z + look.z * 3.0,
                            serverPlayer.yRot + 180f, 0f)
                        npc.yHeadRot = serverPlayer.yRot + 180f
                        npc.skinName = "rct:champion_cynthia_03a5"
                        npc.customName = Component.literal("난천")
                        level.addFreshEntity(npc)
                        logger.info("NPC CHECK placed {}", npc.id)
                    }
                    next(1)
                }
                1 -> if (since(40)) {
                    shot("0-before")
                    server.execute {
                        val serverPlayer = server.playerList.getPlayer(player.uuid)!!
                        val npc = serverPlayer.serverLevel().getEntitiesOfClass(jbro.cobblemon.npc.content.NpcEntity::class.java,
                            serverPlayer.boundingBox.inflate(8.0)).first()
                        logger.info("NPC CHECK talk opened: {}", DialogueSessions.start(serverPlayer, "tower_guide",
                            speaker = npc.displayName(), skin = npc.skinName, npc = npc))
                    }
                    next(2)
                }
                2 -> if (client.screen is NpcDialogueScreen && since(30)) {
                    shot("1-talk")
                    next(3)
                }
                3 -> if (since(60)) {
                    shot("2-talk-later")
                    client.screen?.onClose()
                    next(4)
                }
                4 -> if (since(30)) {
                    shot("3-after")
                    next(5)
                }
                5 -> if (since(10)) client.stop()
            }
        })
    }
}
