package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * A few lines someone says over the world, outside any battle screen: a trainer's words after a battle, read as a
 * caption in the cinematic letterbox ([CinematicLetterbox]) with the speaker's name plate beside it. Lines are written out like battle narration; the confirm key
 * finishes a line and then moves on, and a finished line also moves on by itself so nobody is held up.
 */
object SceneDialogue {
    private const val CHARACTERS_PER_SECOND = 75.0
    private const val DWELL_MILLIS = 1_600L
    private const val DWELL_PER_CHARACTER_MILLIS = 35L
    private const val DWELL_LIMIT_MILLIS = 6_000L

    private class Scene(val speaker: Component?, val lines: List<Component>, val onFinish: Runnable)

    private var scene: Scene? = null
    private var index = 0
    private var lineStartMillis = 0L
    private var completeMillis = 0L

    internal fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
        // Over the world from the HUD, and over any open screen (a battle held open for its last words) after it.
        HudRenderCallback.EVENT.register { context, _ -> if (Minecraft.getInstance().screen == null) render(context) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterRender(screen).register { _, context, _, _, _ -> render(context) }
            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key, scancode, _ ->
                if (scene == null || !CobblemonUiClient.selectActionKey.matches(key, scancode)) {
                    true
                } else {
                    confirm()
                    false
                }
            }
        }
    }

    /** Says [lines] as [speaker] (no name plate when null), replacing anything being said; [onFinish] runs after. */
    @JvmStatic
    @JvmOverloads
    fun play(speaker: Component?, lines: List<Component>, onFinish: Runnable = Runnable {}) {
        val shown = lines.filter { it.string.isNotBlank() }
        stop()
        if (shown.isEmpty()) {
            onFinish.run()
            return
        }
        scene = Scene(speaker, shown, onFinish)
        CinematicLetterbox.show(LETTERBOX_OWNER)
        startLine(0)
    }

    /** Ends what is being said at once; its finish callback still runs. */
    @JvmStatic
    fun stop() {
        val ending = scene ?: return
        scene = null
        CinematicLetterbox.hide(LETTERBOX_OWNER)
        ending.onFinish.run()
    }

    @JvmStatic
    fun isActive(): Boolean = scene != null

    /** How long [lines] take when nobody presses a key, so a camera held over them can be sized to match. */
    @JvmStatic
    fun expectedMillis(lines: List<Component>): Long = lines.sumOf { line ->
        val characters = line.string.length
        (characters * 1000.0 / CHARACTERS_PER_SECOND).toLong() + dwell(characters)
    }

    private fun dwell(characters: Int): Long =
        (DWELL_MILLIS + characters * DWELL_PER_CHARACTER_MILLIS).coerceAtMost(DWELL_LIMIT_MILLIS)

    private fun startLine(next: Int) {
        index = next
        lineStartMillis = System.currentTimeMillis()
        completeMillis = 0L
    }

    private fun shownCharacters(now: Long): Int = ((now - lineStartMillis) / 1000.0 * CHARACTERS_PER_SECOND).toInt()

    private fun tick(client: Minecraft) {
        val current = scene ?: return
        val line = current.lines[index].string
        val now = System.currentTimeMillis()
        if (completeMillis == 0L && shownCharacters(now) >= line.length) completeMillis = now
        // In a screen the key arrives through the screen's key events instead.
        if (client.screen == null) {
            while (scene != null && CobblemonUiClient.selectActionKey.consumeClick()) confirm()
        }
        if (scene === current && completeMillis != 0L && now - completeMillis >= dwell(line.length)) next()
    }

    /** The confirm key: finishes the line being written, as the battle message box does, or moves on from it. */
    private fun confirm() {
        val current = scene ?: return
        val now = System.currentTimeMillis()
        if (completeMillis == 0L) {
            val line = current.lines[index].string
            lineStartMillis = now - (line.length * 1000.0 / CHARACTERS_PER_SECOND).toLong() - 1
            completeMillis = now
            BattleUiSounds.click()
        } else {
            next()
        }
    }

    private fun next() {
        val current = scene ?: return
        if (index + 1 < current.lines.size) {
            BattleUiSounds.click()
            startLine(index + 1)
        } else {
            stop()
        }
    }

    private fun render(context: GuiGraphics) {
        val current = scene ?: return
        if (Minecraft.getInstance().options.hideGui) return
        val line = current.lines[index].string
        // Read in the letterbox's bottom bar, the way a cutscene subtitles its speaker.
        CinematicLetterbox.renderCaption(context, current.speaker, null, line,
            shownCharacters(System.currentTimeMillis()), more = true)
    }

    private const val LETTERBOX_OWNER = "scene_dialogue"
}
