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
import jbro.cobblemon.mcc.api.presentation.TrainerScenes
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
 * battle against Cynthia (two Pokemon) with her bundled scene lines, picks moves, captures each scene (battle start,
 * last Pokemon, the win), then starts a second battle to count the trainers standing. Screenshots land in `screenshots/` as
 * `trainer-scene-<step>.png`; the log reports each check as `SCENE CHECK`.
 */
internal object TrainerSceneCaptureHarness {
    private const val WORLD = "scene-capture"
    private const val TAG = "mcc_managed_trainer"
    /** `MCC_SCENE_CAPTURE_LEAVES=1` walls the battle in with leaves, to see the shader pack's see-through. */
    private val LEAVES = System.getenv("MCC_SCENE_CAPTURE_LEAVES") == "1"
    /**
     * `MCC_SCENE_CAPTURE_STAGE=<gym|elite_four|champion>` tags the first battle as that League stage, so it opens with
     * the versus intro; the intro is captured as `trainer-scene-0-versus-<n>.png` and the log reports when the battle
     * opened after it.
     */
    private val STAGE = System.getenv("MCC_SCENE_CAPTURE_STAGE")?.trim()?.takeIf { it.isNotEmpty() }
    /**
     * `MCC_SCENE_CAPTURE_TEAM=<team>` fights the first battle against a bundled League team (`cynthia_hard`) instead of
     * two Magikarp, and logs the opponents' levels on the server and as the client shows them: a check that a team
     * above Cobblemon's maximum level keeps its levels into the battle.
     */
    private val TEAM = System.getenv("MCC_SCENE_CAPTURE_TEAM")?.trim()?.takeIf { it.isNotEmpty() }
    /** `MCC_SCENE_CAPTURE_MEMBERS=<species,...>` keeps only those members of [TEAM]. */
    private val MEMBERS = System.getenv("MCC_SCENE_CAPTURE_MEMBERS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    /** `MCC_SCENE_CAPTURE_MECHANIC=<MEGA|TERA|...>` allows that battle mechanic, as the League's challenge would. */
    private val MECHANIC = System.getenv("MCC_SCENE_CAPTURE_MECHANIC")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(jbro.cobblemon.mcc.api.rules.MajorBattleMechanic::valueOf)
    /**
     * `MCC_SCENE_CAPTURE_TRANSCRIPT=1` opens the battle history with its key once the last-Pokemon lines are over and
     * captures it as `trainer-scene-3-transcript.png`, to see the trainer's words kept there.
     */
    private val TRANSCRIPT = System.getenv("MCC_SCENE_CAPTURE_TRANSCRIPT") == "1"
    private val SKIN = TrainerResourceSkin("rctmod:textures/trainers/single/champion_cynthia_03a5.png")
    private const val CYNTHIA = "trainer.more_cobblemon_contents_league_challenge.cynthia.scene"
    // The bundled Cynthia lines, the way the league catalog hands them over.
    private val CYNTHIA_SCENES = TrainerScenes(mapOf(
        TrainerScenes.Moment.BATTLE_START to listOf("$CYNTHIA.battle_start.0"),
        TrainerScenes.Moment.LAST_POKEMON to listOf("$CYNTHIA.last_pokemon.0", "$CYNTHIA.last_pokemon.1"),
        TrainerScenes.Moment.PLAYER_WON to listOf("$CYNTHIA.player_won.0", "$CYNTHIA.player_won.1"),
    ))

    fun installFromEnvironment() {
        if (System.getenv("MCC_SCENE_CAPTURE") != "1" || !FabricLoader.getInstance().isDevelopmentEnvironment) return
        val logger = LoggerFactory.getLogger("trainer-scene-capture")
        var phase = 0
        var ticks = 0
        var phaseTick = 0
        val bodies = AtomicInteger(-1)
        var sceneTracked = false
        var sceneStart = 0
        var scenes = 0
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
                1 -> if (CobblemonClient.battle == null) {
                    val waited = ticks - phaseTick
                    if (jbro.cobblemon.ui.extended.transition.BattleVersusIntro.active && waited % 12 == 0) shot("0-versus-$waited")
                    if (STAGE != null && waited == 1) logger.info("SCENE CHECK waiting for the {} versus intro", STAGE)
                } else if (since(10)) {
                    if (STAGE != null && !versusLogged) {
                        versusLogged = true
                        logger.info("SCENE CHECK battle opened {} ticks after the start request", ticks - phaseTick)
                    }
                    countBodies()
                    if (TEAM != null) logger.info("SCENE CHECK client opponent levels {}", CobblemonClient.battle!!.side2.actors
                        .flatMap { it.activePokemon }.mapNotNull { it.battlePokemon }.map { "${it.species.showdownId()}=${it.level}" })
                    if (TEAM != null) server.execute { logOpponentStats(server.playerList.getPlayer(player.uuid)!!, logger) }
                    shot("1-start")
                    next(if (LEAVES) 2 else 3)
                }
                // With the leaf wall up: the battle camera's shots through it, one per shot change (8.5 s apart).
                2 -> {
                    val waited = ticks - phaseTick
                    if (waited in listOf(40, 210, 380)) shot("2-through-${waited / 20}s")
                    if (waited > 390) next(3)
                }
                3 -> {
                    // Each scene the trainer plays (battle start, last Pokemon, the win) is captured once it has settled.
                    if (SceneDialogue.isActive()) {
                        if (!sceneTracked) {
                            sceneTracked = true
                            sceneStart = ticks
                            scenes++
                            logger.info("SCENE CHECK scene {} started (battle open: {})", scenes, CobblemonClient.battle != null)
                        }
                        if (ticks - sceneStart == 24) shot("3-scene-$scenes")
                        // Scenes wait for the confirm key: after the shot, press it through the lines.
                        if (ticks - sceneStart > 30 && (ticks - sceneStart) % 8 == 0) {
                            val window = client.window.window
                            client.keyboardHandler.keyPress(window, org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0)
                            client.keyboardHandler.keyPress(window, org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0)
                        }
                    } else {
                        sceneTracked = false
                        if (TRANSCRIPT && scenes == 2 && transcriptTick < 30 && client.screen is BattleGUI) {
                            val window = client.window.window
                            fun press(action: Int) = client.keyboardHandler.keyPress(window,
                                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT, 0, action, 0)
                            when (transcriptTick++) {
                                0 -> { press(org.lwjgl.glfw.GLFW.GLFW_PRESS); press(org.lwjgl.glfw.GLFW.GLFW_RELEASE) }
                                12 -> {
                                    val said = jbro.cobblemon.ui.extended.BattleLog.getEntries().mapNotNull { it.dialogue }
                                    logger.info("SCENE CHECK transcript open={} dialogue entries={} {}",
                                        jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.isOpen, said.size,
                                        said.map { "${it.speaker?.string}|left=${it.left}|${it.lines.joinToString(" / ") { l -> l.string }}" })
                                    shot("3-transcript")
                                }
                                14 -> {
                                    // From the top: the opening lines, said before the first turn.
                                    client.keyboardHandler.keyPress(window, org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0)
                                    client.keyboardHandler.keyPress(window, org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0)
                                }
                                17 -> shot("3-transcript-top")
                                20 -> { press(org.lwjgl.glfw.GLFW.GLFW_PRESS); press(org.lwjgl.glfw.GLFW.GLFW_RELEASE) }
                            }
                            return@EndTick
                        }
                        pickMove(client, logger)
                        if (CobblemonClient.battle == null && scenes > 0) {
                            logger.info("SCENE CHECK scenes played: {} (expected 3)", scenes)
                            next(4)
                        }
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
        if (TEAM != null) logger.info("SCENE CHECK opponent before pick {}", battle.side2.actors.flatMap { it.activePokemon }
            .mapNotNull { it.battlePokemon }.map { "${it.species.showdownId()} ${it.properties.asString()}" })
        gui.selectAction(request, MoveActionResponse(move.id, target?.getPNX(), null))
        logger.info("SCENE CHECK picked {} at {}", move.id, target?.getPNX())
        return true
    }

    private var captured = false
    private var transcriptTick = 0
    private var versusLogged = false

    /**
     * Two walls of leaves along the battle, four blocks to either side of the line from the player to where the
     * trainer stands (eight blocks ahead), so the side shots look through them.
     */
    private fun wallInWithLeaves(player: ServerPlayer) {
        val level = player.serverLevel()
        val yaw = Math.toRadians(player.yRot.toDouble())
        val forward = net.minecraft.world.phys.Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw))
        val side = net.minecraft.world.phys.Vec3(-forward.z, 0.0, forward.x)
        val leaves = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true)
        for (step in -1..9) for (sign in listOf(-4.0, 4.0)) for (rise in 0..4) {
            val at = player.position().add(forward.scale(step.toDouble())).add(side.scale(sign))
            val pos = net.minecraft.core.BlockPos.containing(at.x, player.y + rise, at.z)
            if (level.getBlockState(pos).isAir) level.setBlockAndUpdate(pos, leaves)
        }
    }

    private fun startBattle(player: ServerPlayer, logger: org.slf4j.Logger, playScene: Boolean) {
        if (LEAVES && playScene) wallInWithLeaves(player)
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
            trainerNameKey = "trainer.more_cobblemon_contents_league_challenge.cynthia",
            lockedParty = ManagedPveBattles.snapshotParty(player, 100),
            opponentProperties = TEAM?.takeIf { playScene }?.let(::bundledTeam)
                ?.filter { member -> MEMBERS == null || member.substringBefore(' ') in MEMBERS }
                ?: listOf("magikarp level=5 moves=splash", "magikarp level=5 moves=splash"),
            mechanicFlags = jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.fromMajor(MECHANIC.takeIf { playScene }),
            appearance = SKIN,
            stage = if (playScene) STAGE else null,
            scenes = if (playScene) CYNTHIA_SCENES else TrainerScenes.NONE,
        )
        val id = ManagedPveBattles.start(player, request) { outcome -> logger.info("SCENE CHECK battle outcome {}", outcome) }
        logger.info("SCENE CHECK managed battle started: {}", id)
        if (TEAM != null && playScene) {
            val battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player)
            logger.info("SCENE CHECK server opponent levels {} (Cobblemon maximum {})", battle?.actors
                ?.filter { it.uuid != player.uuid }?.flatMap { it.pokemonList }
                ?.map { "${it.effectedPokemon.species.showdownId()}=${it.effectedPokemon.level}" },
                Cobblemon.config.maxPokemonLevel)
        }
    }

    /**
     * The opponents' stats as Cobblemon holds them and the maximum HP Showdown fights with (from the trainer's own
     * request), to see a level above Cobblemon's maximum carried into the numbers and not only the label.
     */
    private fun logOpponentStats(player: ServerPlayer, logger: org.slf4j.Logger) {
        val battle = com.cobblemon.mod.common.battles.BattleRegistry.getBattleByParticipatingPlayer(player) ?: return
        val trainer = battle.actors.firstOrNull { it.uuid != player.uuid } ?: return
        trainer.pokemonList.forEach { battlePokemon ->
            val pokemon = battlePokemon.effectedPokemon
            logger.info("SCENE CHECK cobblemon stats {} L{} hp={} atk={} spa={} spe={}", pokemon.species.showdownId(), pokemon.level,
                pokemon.maxHealth, pokemon.getStat(com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK),
                pokemon.getStat(com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK),
                pokemon.getStat(com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED))
        }
        trainer.request?.side?.pokemon?.forEach { logger.info("SCENE CHECK showdown {} condition={}", it.details, it.condition) }
            ?: logger.info("SCENE CHECK showdown request not in yet")
    }

    /** A bundled League team's members, as the League hands them to [ManagedPveBattles]. */
    private fun bundledTeam(name: String): List<String> {
        val path = "data/more_cobblemon_contents_league_challenge/league-challenge/teams/$name.json"
        val stream = checkNotNull(TrainerSceneCaptureHarness::class.java.classLoader.getResourceAsStream(path)) { "No bundled team $name" }
        return stream.reader(Charsets.UTF_8).use { com.google.gson.JsonParser.parseReader(it) }
            .asJsonObject.getAsJsonArray("pokemon").map { it.asString }
    }
}
