package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Development-only remote control for checking screens in one running client. Set `MCC_HUB_DRIVE` to a command file:
 * each tick the client runs the lines appended since the last tick, one at a time, and appends `ok <n> <line>` or
 * `err <n> <line>: <why>` to `<file>.out`. Lines:
 * `cmd <command>` runs a command as the player with full permission, `tab <id>` selects a hub tab, `shot <name>` saves
 * `screenshots/<name>.png`, `wait <ticks>`, `scale <n>`, `theme <style.palette>`, `press <label>` presses the first
 * button whose label contains it, `click <x> <y>` clicks at GUI coordinates, `move <x> <y>` moves the cursor there,
 * `key <glfw code>` presses a key on the screen, `esc` closes the screen, `dump` lists the screen's widgets, `hook <name> <args>` runs a content mod's
 * [registerHook], `quit` stops the client.
 */
object MccHubDriveHarness {
    private val hooks = HashMap<String, (Minecraft, List<String>) -> String?>()

    /** Lets a content mod add a `hook <name>` line; the result is logged, a thrown error reported as `err`. */
    fun registerHook(name: String, run: (Minecraft, List<String>) -> String?) {
        hooks[name] = run
    }

    fun installFromEnvironment() {
        val path = System.getenv("MCC_HUB_DRIVE")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return
        val file = File(path)
        val out = File("$path.out")
        val logger = MoreCobblemonContents.LOGGER
        var done = 0
        var waitTicks = 0
        val busy = AtomicBoolean(false)
        var first = true
        fun report(text: String) {
            out.appendText(text + "\n")
            logger.info("Hub drive {}", text)
        }
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (first) {
                first = false
                client.options.pauseOnLostFocus = false
            }
            (client.screen as? AccessibilityOnboardingScreen)?.let { it.onClose(); return@EndTick }
            (client.screen as? BackupConfirmScreen)?.let { warning ->
                val skip = Component.translatable("selectWorld.backupJoinSkipButton").string
                warning.children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string == skip }?.onPress()
                return@EndTick
            }
            client.toasts.clear()
            if (busy.get()) return@EndTick
            if (waitTicks > 0) { waitTicks--; return@EndTick }
            if (!file.exists()) return@EndTick
            val lines = file.readLines()
            if (done >= lines.size) return@EndTick
            val line = lines[done].trim()
            val number = ++done
            if (line.isEmpty() || line.startsWith("#")) return@EndTick
            val word = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            try {
                val note = run(client, word, rest, { waitTicks = it }, busy) ?: ""
                report("ok $number $line${if (note.isEmpty()) "" else " -> $note"}")
            } catch (failure: Throwable) {
                logger.error("Hub drive line {} failed", number, failure)
                report("err $number $line: ${failure.message}")
            }
        })
        logger.info("Hub drive reads {}", file)
    }

    private fun run(client: Minecraft, word: String, rest: String, waitFor: (Int) -> Unit, busy: AtomicBoolean): String? {
        val args = rest.split(' ').filter { it.isNotEmpty() }
        return when (word) {
            "cmd" -> {
                val server = checkNotNull(client.singleplayerServer) { "no integrated server" }
                val id = checkNotNull(client.player).uuid
                server.execute {
                    val player = server.playerList.getPlayer(id) ?: return@execute
                    server.commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(4), rest)
                }
                null
            }
            "tab" -> { checkNotNull(client.screen as? MccHubScreen) { "hub not open" }.selectTab(rest); null }
            "shot" -> {
                busy.set(true)
                Screenshot.grab(client.gameDirectory, "$rest.png", client.mainRenderTarget) { busy.set(false) }
                "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}"
            }
            "wait" -> { waitFor(rest.toInt()); null }
            "scale" -> { client.options.guiScale().set(rest.toInt()); client.resizeDisplay(); null }
            "theme" -> {
                check(CobblemonUiSharedTheme.select(rest)) { "unknown theme" }
                (client.screen as? MccHubScreen)?.rebuild(); null
            }
            "press" -> {
                val screen = checkNotNull(client.screen) { "no screen" }
                val button = screen.children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string.contains(rest) }
                checkNotNull(button) { "no button with $rest" }.onPress(); null
            }
            "click" -> {
                val screen = checkNotNull(client.screen) { "no screen" }
                val (x, y) = args.map(String::toDouble)
                val hit = screen.mouseClicked(x, y, 0)
                screen.mouseReleased(x, y, 0)
                "hit=$hit"
            }
            "move" -> {
                val (x, y) = args.map(String::toDouble)
                val scale = client.window.guiScale
                GLFW.glfwSetCursorPos(client.window.window, x * scale, y * scale); null
            }
            "esc" -> { client.screen?.onClose(); null }
            "key" -> {
                val code = rest.toInt()
                val screen = checkNotNull(client.screen) { "no screen" }
                val hit = screen.keyPressed(code, 0, 0)
                screen.keyReleased(code, 0, 0)
                "hit=$hit"
            }
            "dump" -> {
                val screen = checkNotNull(client.screen) { "no screen" }
                screen.children().filterIsInstance<AbstractWidget>().joinToString(" | ") {
                    "${it.javaClass.simpleName}[${it.x},${it.y},${it.width}x${it.height}]'${it.message.string}'"
                }
            }
            "hook" -> {
                val hook = checkNotNull(hooks[args.firstOrNull()]) { "unknown hook" }
                hook(client, args.drop(1))
            }
            "quit" -> { client.stop(); null }
            else -> error("unknown command")
        }
    }
}
