package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import jbro.cobblemon.mcc.api.battle.ManagedPveBattles
import jbro.cobblemon.mcc.api.presentation.BattleScenes
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import jbro.cobblemon.ui.extended.BattleDialogue
import jbro.cobblemon.ui.extended.SceneDialogue
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

/**
 * Development-only check of a managed battle's visible trainer and closing scene. Set `MCC_SCENE_CAPTURE=1` and
 * launch with `--quickPlaySingleplayer scene-capture` (a disposable copy of a capture world): it fights a managed
 * battle against a trainer in Cynthia's skin, picks the first move, plays a closing scene on the trainer when the
 * player wins, then starts a second battle to count the trainers standing. Screenshots land in `screenshots/` as
 * `trainer-scene-<step>.png`; the log reports each check as `SCENE CHECK`.
 */
internal object TrainerSceneCaptureHarness {
    private const val WORLD = "scene-capture"
    private const val TAG = "mcc_managed_trainer"
    private val SKIN = TrainerResourceSkin("rctmod:textures/trainers/single/champion_cynthia_03a5.png")

    fun installFromEnvironment() {
        if (System.getenv("MCC_SCENE_CAPTURE") != "1" || !FabricLoader.getInstance().isDevelopmentEnvironment) return
        val logger = LoggerFactory.getLogger("trainer-scene-capture")
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        val bodies = AtomicInteger(-1)
        var sceneSeen = false
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            ticks++
            if (ticks == 1) client.options.pauseOnLostFocus = false
            check(ticks < 6000) { "Trainer scene capture timed out in phase $phase" }
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
                "The scene capture may only change the disposable $WORLD world"
            }
            fun since(n: Int) = ticks - phaseTick >= n
            fun next(to: Int) { phase = to; phaseTick = ticks }
            fun shot(step: String) {
                Screenshot.grab(client.gameDirectory, "trainer-scene-$step.png", client.mainRenderTarget) {}
                logger.info("SCENE CAPTURE {} (battle={}, scene={}, trainers={})", step, CobblemonClient.battle != null,
                    SceneDialogue.isActive(), bodies.get())
            }
            fun countBodies() = server.execute {
                val serverPlayer = server.playerList.getPlayer(player.uuid) ?: return@execute
                bodies.set(serverPlayer.serverLevel().getEntitiesOfClass(NPCEntity::class.java,
                    serverPlayer.boundingBox.inflate(64.0)) { TAG in it.tags }.size)
            }
            // Battle narration is skipped so the command menu (and later the closing scene) comes at once.
            if (phase in 1..3 && BattleDialogue.hasPending()) BattleDialogue.clear()
            when (phase) {
                0 -> if (client.screen == null) {
                    client.options.guiScale().set(2)
                    client.resizeDisplay()
                    server.execute { startBattle(server.playerList.getPlayer(player.uuid)!!, logger, playScene = true) }
                    next(1)
                }
                // Frames in quick succession catch the trainer's send-out motion.
                1 -> if (CobblemonClient.battle != null && (ticks - phaseTick) % 4 == 0) {
                    val frame = (ticks - phaseTick) / 4
                    if (frame == 1) countBodies()
                    shot("1-start-$frame")
                    if (frame >= 8) next(2)
                }
                2 -> if (since(40) && pickMove(client, logger)) {
                    next(3)
                }
                3 -> {
                    // Every turn until the battle ends, should one hit not be enough.
                    pickMove(client, logger)
                    if (SceneDialogue.isActive() && !sceneSeen) {
                        sceneSeen = true
                        phaseTick = ticks
                    }
                    if (sceneSeen && (ticks - phaseTick) in listOf(4, 12, 20, 30)) shot("3-scene-${ticks - phaseTick}")
                    if (sceneSeen && since(45)) {
                        countBodies()
                        logger.info("SCENE CHECK battle still open while the scene plays: {}", CobblemonClient.battle != null)
                        shot("3-scene")
                        next(4)
                    }
                }
                4 -> if (!SceneDialogue.isActive() && CobblemonClient.battle == null) next(5)
                5 -> if (since(40)) {
                    countBodies()
                    shot("4-after")
                    server.execute { startBattle(server.playerList.getPlayer(player.uuid)!!, logger, playScene = false) }
                    next(6)
                }
                6 -> if (since(60) && CobblemonClient.battle != null) {
                    countBodies()
                    next(7)
                }
                7 -> if (since(5)) {
                    shot("5-second-battle")
                    logger.info("SCENE CHECK trainers standing during the second battle: {} (expected 1)", bodies.get())
                    next(8)
                }
                8 -> if (since(20)) client.stop()
            }
        })
    }

    /** Answers the open move request with the first usable move at the opposing Pokemon; false when none is open. */
    private fun pickMove(client: net.minecraft.client.Minecraft, logger: org.slf4j.Logger): Boolean {
        val battle = CobblemonClient.battle ?: return false
        val gui = client.screen as? BattleGUI ?: return false
        if (!battle.mustChoose) return false
        val request = battle.getFirstUnansweredRequest() ?: return false
        val move = request.moveSet?.moves?.firstOrNull { !it.disabled } ?: return false
        val target = request.activePokemon.getOppositeOpponent() as? ActiveClientBattlePokemon
        if (!captured) {
            captured = true
            Screenshot.grab(client.gameDirectory, "trainer-scene-2-battle.png", client.mainRenderTarget) {}
        }
        gui.selectAction(request, MoveActionResponse(move.id, target?.getPNX(), null))
        logger.info("SCENE CHECK picked {} at {}", move.id, target?.getPNX())
        return true
    }

    private var captured = false

    private fun startBattle(player: ServerPlayer, logger: org.slf4j.Logger, playScene: Boolean) {
        val party = Cobblemon.storage.getParty(player)
        party.clearParty()
        party.add(PokemonProperties.parse("mewtwo level=100").create().also { mewtwo ->
            // One sure, immediate hit, so the battle ends on the first turn.
            mewtwo.moveSet.clear()
            mewtwo.moveSet.add(checkNotNull(Moves.getByName("psystrike")).create())
        })
        val request = ManagedPveBattles.Request(
            transactionId = UUID.randomUUID(),
            contentId = "more_cobblemon_contents_league_challenge:scene_capture",
            trainerId = "more_cobblemon_contents_league_challenge:scene_capture",
            trainerNameKey = "npc.more_cobblemon_contents.managed_trainer",
            lockedParty = ManagedPveBattles.snapshotParty(player, 100),
            opponentProperties = listOf("magikarp level=5 moves=splash"),
            appearance = SKIN,
        )
        val id = ManagedPveBattles.start(player, request) { outcome ->
            logger.info("SCENE CHECK battle outcome {}", outcome)
            if (!playScene || outcome != ManagedPveBattles.Outcome.WIN) return@start
            val online = player.server.playerList.getPlayer(player.uuid) ?: return@start
            val trainer = online.serverLevel().getEntitiesOfClass(NPCEntity::class.java, online.boundingBox.inflate(64.0)) {
                TAG in it.tags
            }.firstOrNull()
            logger.info("SCENE CHECK closing scene focuses {}", trainer?.uuid)
            BattleScenes.play(online, trainer, Component.literal("난천"), listOf(
                Component.literal("훌륭해. 너와 포켓몬의 유대가 느껴졌어."),
                Component.literal("오늘의 승부는 오래 기억할 것 같아."),
            ))
        }
        logger.info("SCENE CHECK managed battle started: {}", id)
    }
}
