package jbro.cobblemon.ui.extended

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.entity.npc.NPCEntity
import jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Development-only visual check of the battle screens. Set `BATTLE_UI_CAPTURE` to a wild Pokemon (for example
 * `rattata level=8`) and launch with `--quickPlaySingleplayer <world>`: once the world is loaded the player's party is
 * topped up from `BATTLE_UI_CAPTURE_PARTY` (default four Pokemon), the wild Pokemon is spawned next to the player and
 * a battle starts through the integrated server. Once the command menu shows, `BATTLE_UI_CAPTURE_STEPS` runs:
 * `ready` skips pending battle narration and waits for the command menu, `wait:N` ticks, `cap:name` screenshots to `screenshots/battle-name-<locale>-<w>x<h>.png`, `key:down|up|left|right|
 * space|esc` presses a key on the battle screen, `mouse:x,y` moves the cursor to GUI coordinates, `fight` and `switch`
 * open those menus as their tiles would, and `info` toggles the information overlay. Then the client stops.
 * `confirm` presses the bound select key, `log` toggles the battle log, `tile:N` presses the Nth command.
 * `settings` opens the mod's settings screen; only `wait` and `cap` steps may follow it.
 * `entry:<kind>` (`legendary`, `wild`, `trainer`) or `entry:<species>` plays the battle entry transition over the
 * battle without blocking input, and `reveal` clears it. `BATTLE_UI_CAPTURE_CHALLENGE=1` challenges the wild Pokemon
 * as the R key does, so steps run from the transition's covered screen on. `theme:<id>` switches the battle theme.
 * `BATTLE_UI_CAPTURE_TRAINER=single|double|triple` battles a disposable NPC trainer in that format instead of the
 * wild Pokemon, for the target and forfeit screens.
 * `BATTLE_UI_CAPTURE_LOCALE` (default `ko_kr`) and `BATTLE_UI_CAPTURE_GUI_SCALE` (1-4) set up the client first.
 * `BATTLE_UI_CAPTURE_THEME` picks a battle theme by id for the run, without saving it.
 */
object BattleUiCaptureHarness {
    private val logger = CobblemonUi.LOGGER

    private const val DEFAULT_STEPS = "ready,wait:20,cap:general,key:down,wait:15,cap:focus,key:down,wait:2,cap:moving," +
        "wait:15,key:up,wait:10,fight,wait:10,key:down,wait:15,cap:moves,key:esc,ready,switch,wait:10,key:right," +
        "wait:15,cap:switch,key:esc,ready,info,wait:30,cap:info,info,wait:5"

    fun installFromEnvironment() {
        val wild = System.getenv("BATTLE_UI_CAPTURE")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return
        val locale = System.getenv("BATTLE_UI_CAPTURE_LOCALE")?.trim()?.takeIf { it.isNotEmpty() } ?: "ko_kr"
        System.getenv("BATTLE_UI_CAPTURE_THEME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!jbro.cobblemon.ui.extended.ui.shared.BattleUiThemes.select(it)) logger.warn("Unknown battle theme {}", it)
        }
        val guiScale = System.getenv("BATTLE_UI_CAPTURE_GUI_SCALE")?.trim()?.toIntOrNull()?.takeIf { it in 1..4 }
        val party = (System.getenv("BATTLE_UI_CAPTURE_PARTY") ?: "pikachu,charizard,gardevoir,lucario")
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val steps = ArrayDeque((System.getenv("BATTLE_UI_CAPTURE_STEPS") ?: DEFAULT_STEPS)
            .split(',').map(String::trim).filter(String::isNotEmpty))

        var guiScaleApplied = guiScale == null
        val languageReady = AtomicBoolean(false)
        val languageFailure = AtomicReference<Throwable?>()
        var languageRequested = false
        var partyRequested = false
        var battleRequested = false
        var ticks = 0
        var waitTicks = 0
        var settings: Screen? = null
        val captured = AtomicBoolean(true)
        var done = false
        val trainerFormat = when (System.getenv("BATTLE_UI_CAPTURE_TRAINER")?.trim()) {
            null, "" -> null
            "single" -> BattleFormat.GEN_9_SINGLES
            "double" -> BattleFormat.GEN_9_DOUBLES
            "triple" -> BattleFormat.GEN_9_TRIPLES
            else -> error("Unknown capture trainer format")
        }
        val trainer = AtomicReference<NPCEntity?>()
        var trainerTicks = 0
        var readyTicks = 0
        // Challenge the wild Pokemon as the R key does, through Cobblemon's challenge handling, instead of starting
        // the battle on the server directly; that is the path the battle entry transition holds.
        val challenge = System.getenv("BATTLE_UI_CAPTURE_CHALLENGE") == "1"
        val wildId = java.util.concurrent.atomic.AtomicInteger(-1)
        var challengeSent = false
        var aimTicks = 0

        var idleTicks = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (done) return@EndTick
            if (client.level == null && ++idleTicks % 400 == 0) {
                logger.info("Battle capture is still waiting outside a world on {}", client.screen?.javaClass?.name)
            }
            // The capture worlds use experimental settings; press through the warning as a player would.
            (client.screen as? BackupConfirmScreen)?.let { warning ->
                val skip = Component.translatable("selectWorld.backupJoinSkipButton").string
                warning.children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string == skip }?.let {
                    it.onPress()
                    logger.info("Battle capture skipped the experimental world warning")
                }
                return@EndTick
            }
            if (!guiScaleApplied) {
                client.options.guiScale().set(checkNotNull(guiScale)
)
                client.resizeDisplay()
                guiScaleApplied = true
                return@EndTick
            }
            languageFailure.get()?.let { throw IllegalStateException("Battle capture language reload failed", it) }
            if (!languageReady.get()) {
                if (!languageRequested && client.overlay == null && (client.screen != null || client.level != null)) {
                    client.options.languageCode = locale
                    client.languageManager.setSelected(locale
)
                    languageRequested = true
                    client.reloadResourcePacks().whenComplete { _, error ->
                        if (error == null) languageReady.set(true) else languageFailure.set(error)
                    }
                }
                return@EndTick
            }
            val player = client.player ?: return@EndTick
            if (client.level == null || client.overlay != null) return@EndTick
            val server = checkNotNull(client.singleplayerServer) { "The battle capture needs a singleplayer world" }
            client.toasts.clear()
            if (!partyRequested) {
                val have = CobblemonClient.storage.party.count { it != null }
                val name = player.gameProfile.name
                server.execute {
                    party.drop(have).forEach { species ->
                        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "givepokemonother $name $species level=50")
                    }
                    server.commands.performPrefixedCommand(server.createCommandSourceStack(), "healpokemon $name")
                }
                logger.info("Battle capture party had {} Pokemon; gave {}", have, party.drop(have))
                partyRequested = true
                return@EndTick
            }
            ticks += 1
            if (!battleRequested) {
                // The party sync reaches the client a few ticks after the server gives the Pokemon.
                if (ticks < 40) return@EndTick
                val uuid = player.uuid
                if (trainerFormat != null) {
                    // A trainer needs a few ticks in the world before Cobblemon accepts a challenge.
                    if (trainerTicks == 0) server.execute {
                        val serverPlayer = checkNotNull(server.playerList.getPlayer(uuid))
                        val world = serverPlayer.serverLevel()
                        val npc = NPCEntity(world)
                        npc.npc = NPCClasses.classes.sortedBy { it.id.toString() }.first()
                        val npcParty = NPCPartyStore(npc)
                        listOf("charizard", "squirtle", "meowth").forEach { species ->
                            check(npcParty.add(PokemonProperties.parse("$species level=50").create())) { "Could not add $species" }
                        }
                        npcParty.initialize()
                        npc.party = npcParty
                        npc.moveTo(serverPlayer.x + 3.0, serverPlayer.y, serverPlayer.z + 3.0, 150f, 0f)
                        check(world.addFreshEntity(npc)) { "Could not spawn the capture trainer" }
                        trainer.set(npc)
                    }
                    if (++trainerTicks < 30 || trainer.get() == null) return@EndTick
                    server.execute {
                        val serverPlayer = checkNotNull(server.playerList.getPlayer(uuid))
                        logger.info("Battle capture started a trainer battle: {}",
                            BattleBuilder.pvn(serverPlayer, checkNotNull(trainer.get()), battleFormat = trainerFormat))
                    }
                } else server.execute {
                    val serverPlayer = checkNotNull(server.playerList.getPlayer(uuid))
                    val world = serverPlayer.serverLevel()
                    val entity = PokemonProperties.parse(wild).createEntity(world)
                    // A challenge needs a clear line of sight, so take the first side of the player that has one.
                    val eye = serverPlayer.eyePosition
                    val spot = (if (challenge) (0 until 8).map { Math.toRadians(it * 45.0 + 34.0) } else emptyList())
                        .map { net.minecraft.world.phys.Vec3(serverPlayer.x + 3.6 * Math.cos(it), serverPlayer.y,
                            serverPlayer.z + 3.6 * Math.sin(it)) }
                        .firstOrNull { world.clip(net.minecraft.world.level.ClipContext(eye, it.add(0.0, 1.0, 0.0),
                            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                            serverPlayer)).type == net.minecraft.world.phys.HitResult.Type.MISS }
                        ?: net.minecraft.world.phys.Vec3(serverPlayer.x + 3.0, serverPlayer.y, serverPlayer.z + 2.0)
                    entity.moveTo(spot.x, spot.y, spot.z, 150f, 0f)
                    world.addFreshEntity(entity)
                    if (challenge) wildId.set(entity.id)
                    else logger.info("Battle capture started a battle with {}: {}", wild, BattleBuilder.pve(serverPlayer, entity))
                }
                battleRequested = true
                ticks = 0
                return@EndTick
            }
            if (challenge && !challengeSent && trainerFormat == null) {
                val id = wildId.get()
                val target = if (id >= 0) client.level?.getEntity(id) else null
                if (target == null) {
                    if (ticks > 200) error("The capture Pokemon did not reach the client")
                    return@EndTick
                }
                // Cobblemon only accepts a challenge to the Pokemon the player is looking at.
                player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    target.position().add(0.0, target.bbHeight / 2.0, 0.0))
                if (++aimTicks < 6) return@EndTick
                val lead = checkNotNull(CobblemonClient.storage.party.firstOrNull { it != null }) { "No lead Pokemon" }.uuid
                com.cobblemon.mod.common.net.messages.server.BattleChallengePacket(id, lead, BattleFormat.GEN_9_SINGLES)
                    .sendToServer()
                logger.info("Battle capture challenged {} as the R key would", wild)
                challengeSent = true
                ticks = 0
                return@EndTick
            }
            val battleScreen = client.screen as? BattleGUI
            // The entry transition's input screen, while it holds the world covered, takes steps as well.
            val onEntry = client.screen is jbro.cobblemon.ui.extended.transition.BattleEntryScreen
            // The settings screen, once a step opens it, takes waits and captures in the battle screen's place.
            val onSettings = settings != null && client.screen === settings
            if (battleScreen == null && !onSettings && !onEntry) {
                if (ticks > 1200) error("The battle screen did not show")
                return@EndTick
            }
            if (!captured.get()) return@EndTick
            if (waitTicks > 0) {
                waitTicks -= 1
                return@EndTick
            }
            if (steps.firstOrNull() == "ready") {
                if (battleScreen == null) return@EndTick
                // Narration hides the command menu until it is read; the menu itself arrives a frame after Escape.
                BattleDialogue.clear()
                if (battleScreen.getCurrentActionSelection() !is BattleGeneralActionSelection) {
                    if (++readyTicks > 400) error("The battle command menu did not come back")
                    return@EndTick
                }
                readyTicks = 0
            }
            val step = steps.removeFirstOrNull()
            if (step == null) {
                done = true
                client.stop()
                return@EndTick
            }
            val (verb, argument) = step.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            fun battle() = checkNotNull(battleScreen) { "$step needs the battle screen" }
            when (verb) {
                "ready" -> Unit
                "wait" -> waitTicks = argument.toInt()
                "cap" -> {
                    captured.set(false)
                    val file = "battle-$argument-${client.languageManager.selected}-" +
                        "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
                    Screenshot.grab(client.gameDirectory, file, client.mainRenderTarget) { result ->
                        logger.info("Battle capture {}: {}", file, result.string)
                        captured.set(true)
                    }
                }
                "key" -> {
                    val key = KEYS[argument] ?: error("Unknown capture key $argument")
                    battle().keyPressed(key, 0, 0)
                }
                "mouse" -> {
                    val (x, y) = argument.split(';', '/').map(String::toDouble)
                    val factor = client.window.guiScale
                    GLFW.glfwSetCursorPos(client.window.window, x * factor, y * factor)
                }
                "fight", "switch" -> {
                    val general = checkNotNull(battle().getCurrentActionSelection() as? BattleGeneralActionSelection) {
                        "$verb needs the general command menu"
                    }
                    if (verb == "fight") general.tiles.first().onClick.invoke()
                    else battle().changeActionSelection(BattleSwitchPokemonSelection(battle(), general.request))
                }
                "info" -> BattleInfoPanel.toggle()
                "confirm" -> {
                    val key = KeyBindingHelper.getBoundKeyOf(CobblemonUiClient.selectActionKey).value
                    battle().keyPressed(key, 0, 0)
                    BattleDialogue.releaseConfirm(key, 0)
                }
                "log" -> {
                    val key = KeyBindingHelper.getBoundKeyOf(CobblemonUiClient.toggleLogKey).value
                    BattleTranscriptOverlay.keyPressed(key, 0)
                    BattleTranscriptOverlay.releaseKey(key, 0)
                }
                "tile" -> {
                    val general = checkNotNull(battle().getCurrentActionSelection() as? BattleGeneralActionSelection) {
                        "tile needs the general command menu"
                    }
                    general.tiles[argument.toInt()].onClick.invoke()
                }
                "entry" -> {
                    // A kind id plays that kind; any other name plays the transition for that species.
                    val kind = jbro.cobblemon.ui.extended.transition.BattleEntryKind.fromId(argument)
                    if (kind != null) jbro.cobblemon.ui.extended.transition.BattleEntryTransition.play(kind, null, false)
                    else jbro.cobblemon.ui.extended.transition.BattleEntryTransition.play(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", argument), false, false)
                }
                "reveal" -> jbro.cobblemon.ui.extended.transition.BattleEntryTransition.reveal()
                "theme" -> check(jbro.cobblemon.ui.extended.ui.shared.BattleUiThemes.select(argument)) { "Unknown battle theme $argument" }
                "settings" -> {
                    settings = ClothConfigScreenBuilder.create(client.screen ?: error("settings needs a screen to return to"))
                    client.setScreen(settings)
                }
                else -> error("Unknown capture step $step")
            }
            logger.info("Battle capture step {}", step)
        })
    }

    private val KEYS = mapOf(
        "down" to GLFW.GLFW_KEY_DOWN,
        "up" to GLFW.GLFW_KEY_UP,
        "left" to GLFW.GLFW_KEY_LEFT,
        "right" to GLFW.GLFW_KEY_RIGHT,
        "space" to GLFW.GLFW_KEY_SPACE,
        "esc" to GLFW.GLFW_KEY_ESCAPE,
    )
}
