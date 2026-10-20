package jbro.cobblemon.ui.extended

import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiDialogueStyle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW
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
    private class Scene(
        val speaker: Component?,
        val lines: List<Component>,
        val portrait: net.minecraft.world.entity.LivingEntity?,
        val onFinish: Runnable
    ) {
        /** Whether a battle's history holds these lines already. */
        var recorded = false
    }

    private var scene: Scene? = null
    private var index = 0
    // Typing and the confirm key/click/hold, as every message box has them.
    private val advance = DialogueAdvance(
        length = { scene?.lines?.getOrNull(index)?.string?.length },
        advance = { next() },
    )

    internal fun register() {
        // Before the game reads its keys: over the world, the confirm key and a left click are the scene's.
        ClientTickEvents.START_CLIENT_TICK.register { client -> takeWorldInput(client) }
        // Over the world from the HUD, and over any open screen (a battle held open for its last words) after it.
        HudRenderCallback.EVENT.register { context, _ -> if (Minecraft.getInstance().screen == null) render(context) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterRender(screen).register { _, context, _, _, _ -> render(context) }
            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key, scancode, _ ->
                scene == null || !advance.keyPressed(key, scancode)
            }
            ScreenKeyboardEvents.afterKeyRelease(screen).register { _, key, scancode, _ -> advance.keyReleased(key, scancode) }
            // A click while a scene speaks is the scene's, never the battle menu's behind it.
            ScreenMouseEvents.allowMouseClick(screen).register { _, _, _, button ->
                scene == null || !advance.mouseClicked(button) && button != GLFW.GLFW_MOUSE_BUTTON_LEFT
            }
        }
    }

    /** Says [lines] as [speaker] (no name plate when null), replacing anything being said; [onFinish] runs after. */
    @JvmStatic
    @JvmOverloads
    fun play(speaker: Component?, lines: List<Component>, onFinish: Runnable = Runnable {}) =
        play(speaker, lines, null, onFinish)

    /** Like [play], with [portrait] (the one speaking) as the face beside the lines in the battle history. */
    @JvmStatic
    fun play(speaker: Component?, lines: List<Component>, portrait: net.minecraft.world.entity.LivingEntity?, onFinish: Runnable) {
        val shown = lines.filter { it.string.isNotBlank() }
        stop()
        if (shown.isEmpty()) {
            onFinish.run()
            return
        }
        advance.releaseIfUp()
        scene = Scene(speaker, shown, portrait, onFinish)
        // Words said during a battle stay in its history (the Shift transcript).
        record()
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

    /**
     * A battle has just opened. A trainer's opening lines can start before it does (during the versus intro): the
     * lines still on screen then go into the new battle's history.
     */
    @JvmStatic
    fun battleOpened() = record()

    private fun record() {
        val current = scene ?: return
        if (current.recorded) return
        current.recorded = jbro.cobblemon.ui.extended.ui.transcript.TranscriptSources
            .captureDialogue(current.speaker, current.lines, current.portrait)
    }

    /** Legacy typing-duration estimate kept for API compatibility; scenes themselves now wait for confirmation. */
    @JvmStatic
    fun expectedMillis(lines: List<Component>): Long = lines.sumOf { line -> DialogueAdvance.millisFor(line.string.length) }

    private fun startLine(next: Int) {
        index = next
        advance.restart()
    }

    /**
     * With no screen open, a scene takes the confirm key and the attack click (a left click) before the game reads
     * them, so the player neither swings nor mines while someone is speaking.
     */
    private fun takeWorldInput(client: Minecraft) {
        if (scene == null) {
            advance.releaseIfUp()
            return
        }
        if (client.screen != null) return
        while (scene != null && CobblemonUiClient.selectActionKey.consumeClick()) advance.press(DialogueAdvance.KEYBOARD)
        val attack = KeyBindingHelper.getBoundKeyOf(client.options.keyAttack)
        while (client.options.keyAttack.consumeClick()) {
            if (scene != null && attack.type == InputConstants.Type.MOUSE) advance.press(attack.value)
        }
        client.options.keyAttack.setDown(false)
    }

    private fun next() {
        val current = scene ?: return
        if (index + 1 < current.lines.size) {
            startLine(index + 1)
        } else {
            stop()
        }
    }

    private fun render(context: GuiGraphics) {
        val current = scene ?: return
        val client = Minecraft.getInstance()
        if (client.options.hideGui) return
        advance.tick()
        val now = scene ?: return
        // The battle message box, floating over the letterbox's bars.
        context.pose().pushPose()
        context.pose().translate(0f, 0f, CinematicLetterbox.OVER_BARS_Z)
        val box = BattleDialogue.renderMessage(context, now.lines[index], advance.shown())
        now.speaker?.let { speaker ->
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
