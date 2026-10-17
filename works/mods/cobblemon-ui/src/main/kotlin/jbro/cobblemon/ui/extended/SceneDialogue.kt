package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiDialogueStyle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * A few lines someone says over the world, outside any battle screen: a trainer's words after a battle, drawn in the
 * battle message box with the speaker's name above it, floating over the cinematic letterbox ([CinematicLetterbox]). Lines are written out like battle narration; the confirm key
 * finishes a line and then moves on. A line never advances by elapsed time: the speaker and its server-side lifecycle
 * stay with the player until the player closes the last line.
 */
object SceneDialogue {
    private const val CHARACTERS_PER_SECOND = 75.0

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
        // The key held to skip the lines is not for the battle menu they ended over: it waits for a new press.
        BattleConfirmGate.released()
        ending.onFinish.run()
    }

    @JvmStatic
    fun isActive(): Boolean = scene != null

    /** Legacy typing-duration estimate kept for API compatibility; scenes themselves now wait for confirmation. */
    @JvmStatic
    fun expectedMillis(lines: List<Component>): Long = lines.sumOf { line ->
        val characters = line.string.length
        (characters * 1000.0 / CHARACTERS_PER_SECOND).toLong()
    }

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
        val client = Minecraft.getInstance()
        if (client.options.hideGui) return
        // The battle message box, floating over the letterbox's bars.
        context.pose().pushPose()
        context.pose().translate(0f, 0f, CinematicLetterbox.OVER_BARS_Z)
        val box = BattleDialogue.renderMessage(context, current.lines[index], shownCharacters(System.currentTimeMillis()))
        current.speaker?.let { speaker ->
            // The speaker's name on a tab over the box's left edge.
            val font = client.font
            val theme = UiDialogueStyle.theme()
            val width = font.width(speaker) + 16
            val height = font.lineHeight + 8
            val left = box.x + 10
            val top = box.y - height + 2
            UiDialogueStyle.drawPlate(context, theme, font, UiRect(left, top, width, height), speaker, left + 8)
        }
        context.pose().popPose()
    }

    private const val LETTERBOX_OWNER = "scene_dialogue"
}
