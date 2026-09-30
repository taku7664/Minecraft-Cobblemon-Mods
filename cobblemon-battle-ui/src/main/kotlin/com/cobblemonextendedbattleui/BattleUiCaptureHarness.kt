package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screen.world.BackupPromptScreen
import net.minecraft.client.gui.widget.PressableWidget
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
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
 * `BATTLE_UI_CAPTURE_LOCALE` (default `ko_kr`) and `BATTLE_UI_CAPTURE_GUI_SCALE` (1-4) set up the client first.
 */
object BattleUiCaptureHarness {
    private val logger = CobblemonExtendedBattleUI.LOGGER

    private const val DEFAULT_STEPS = "ready,wait:20,cap:general,key:down,wait:15,cap:focus,key:down,wait:2,cap:moving," +
        "wait:15,key:up,wait:10,fight,wait:10,key:down,wait:15,cap:moves,key:esc,ready,switch,wait:10,key:right," +
        "wait:15,cap:switch,key:esc,ready,info,wait:30,cap:info,info,wait:5"

    fun installFromEnvironment() {
        val wild = System.getenv("BATTLE_UI_CAPTURE")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return
        val locale = System.getenv("BATTLE_UI_CAPTURE_LOCALE")?.trim()?.takeIf { it.isNotEmpty() } ?: "ko_kr"
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
        val captured = AtomicBoolean(true)
        var done = false
        var readyTicks = 0

        var idleTicks = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (done) return@EndTick
            if (client.world == null && ++idleTicks % 400 == 0) {
                logger.info("Battle capture is still waiting outside a world on {}", client.currentScreen?.javaClass?.name)
            }
            // The capture worlds use experimental settings; press through the warning as a player would.
            (client.currentScreen as? BackupPromptScreen)?.let { warning ->
                val skip = Text.translatable("selectWorld.backupJoinSkipButton").string
                warning.children().filterIsInstance<PressableWidget>().firstOrNull { it.message.string == skip }?.let {
                    it.onPress()
                    logger.info("Battle capture skipped the experimental world warning")
                }
                return@EndTick
            }
            if (!guiScaleApplied) {
                client.options.guiScale.value = checkNotNull(guiScale)
                client.onResolutionChanged()
                guiScaleApplied = true
                return@EndTick
            }
            languageFailure.get()?.let { throw IllegalStateException("Battle capture language reload failed", it) }
            if (!languageReady.get()) {
                if (!languageRequested && client.overlay == null && (client.currentScreen != null || client.world != null)) {
                    client.options.language = locale
                    client.languageManager.language = locale
                    languageRequested = true
                    client.reloadResources().whenComplete { _, error ->
                        if (error == null) languageReady.set(true) else languageFailure.set(error)
                    }
                }
                return@EndTick
            }
            val player = client.player ?: return@EndTick
            if (client.world == null || client.overlay != null) return@EndTick
            val server = checkNotNull(client.server) { "The battle capture needs a singleplayer world" }
            client.toastManager.clear()
            if (!partyRequested) {
                val have = CobblemonClient.storage.party.count { it != null }
                val name = player.gameProfile.name
                server.execute {
                    party.drop(have).forEach { species ->
                        server.commandManager.executeWithPrefix(server.commandSource, "givepokemonother $name $species level=50")
                    }
                    server.commandManager.executeWithPrefix(server.commandSource, "healpokemon $name")
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
                server.execute {
                    val serverPlayer = checkNotNull(server.playerManager.getPlayer(uuid))
                    val world = serverPlayer.serverWorld
                    val entity = PokemonProperties.parse(wild).createEntity(world)
                    entity.refreshPositionAndAngles(serverPlayer.x + 3.0, serverPlayer.y, serverPlayer.z + 2.0, 150f, 0f)
                    world.spawnEntity(entity)
                    logger.info("Battle capture started a battle with {}: {}", wild, BattleBuilder.pve(serverPlayer, entity))
                }
                battleRequested = true
                ticks = 0
                return@EndTick
            }
            val battleScreen = client.currentScreen as? BattleGUI
            if (battleScreen == null) {
                if (ticks > 1200) error("The battle screen did not show")
                return@EndTick
            }
            if (!captured.get()) return@EndTick
            if (waitTicks > 0) {
                waitTicks -= 1
                return@EndTick
            }
            if (steps.firstOrNull() == "ready") {
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
            when (verb) {
                "ready" -> Unit
                "wait" -> waitTicks = argument.toInt()
                "cap" -> {
                    captured.set(false)
                    val file = "battle-$argument-${client.languageManager.language}-" +
                        "${client.window.scaledWidth}x${client.window.scaledHeight}.png"
                    ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.framebuffer) { result ->
                        logger.info("Battle capture {}: {}", file, result.string)
                        captured.set(true)
                    }
                }
                "key" -> {
                    val key = KEYS[argument] ?: error("Unknown capture key $argument")
                    battleScreen.keyPressed(key, 0, 0)
                }
                "mouse" -> {
                    val (x, y) = argument.split(';', '/').map(String::toDouble)
                    val factor = client.window.scaleFactor
                    GLFW.glfwSetCursorPos(client.window.handle, x * factor, y * factor)
                }
                "fight", "switch" -> {
                    val general = checkNotNull(battleScreen.getCurrentActionSelection() as? BattleGeneralActionSelection) {
                        "$verb needs the general command menu"
                    }
                    if (verb == "fight") general.tiles.first().onClick.invoke()
                    else battleScreen.changeActionSelection(BattleSwitchPokemonSelection(battleScreen, general.request))
                }
                "info" -> BattleInfoPanel.toggle()
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
