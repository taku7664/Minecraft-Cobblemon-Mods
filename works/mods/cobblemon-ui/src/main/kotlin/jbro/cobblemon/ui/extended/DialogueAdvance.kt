package jbro.cobblemon.ui.extended

import com.mojang.blaze3d.platform.InputConstants
import jbro.cobblemon.ui.dialogue.HoldRepeat
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * How every message box moves on: battle narration, a trainer's scene lines, an NPC's talk. A line is written out at
 * [CHARACTERS_PER_SECOND]; the confirm key or a left click first finishes it and then moves on; holding either runs
 * on after a short pause, a step at a time, finishing a line or moving past a finished one. The system's own key
 * repeat does nothing: a press counts once until its release.
 *
 * A box tells it the length of the line on screen ([length], null when there is nothing to read) and what moving on
 * means ([advance], `held` for a hold's repeat step). Call [restart] when a new line comes up, and [tick] every frame
 * the box is shown.
 */
class DialogueAdvance(
    private val length: () -> Int?,
    private val advance: (held: Boolean) -> Unit,
    private val released: () -> Unit = {},
) {
    private var startNanos = System.nanoTime()
    private var finished = false
    private var latched = false
    private var button = KEYBOARD
    // A pause long enough for a single tap, then a quick run.
    private val hold = HoldRepeat(HOLD_DELAY_NANOS, HOLD_STEP_NANOS)

    /** A new line starts being written out. */
    fun restart() {
        startNanos = System.nanoTime()
        finished = false
    }

    /** How many characters of the line are written out so far. */
    fun shown(): Int = if (finished) Int.MAX_VALUE else charactersSince(startNanos)

    /** Whether the line on screen is written out; true when there is none. */
    fun complete(): Boolean = length()?.let { shown() >= it } ?: true

    /** Writes the rest of the line out at once. */
    fun finish() {
        finished = true
    }

    /** Whether a press is held now (until its release, nothing else it sends counts). */
    val isHeld: Boolean get() = latched

    /** The confirm key: true when it was taken. */
    fun keyPressed(keyCode: Int, scanCode: Int): Boolean =
        CobblemonUiClient.selectActionKey.matches(keyCode, scanCode) && press(KEYBOARD)

    /** A left click does what the confirm key does, holding included: true when it was taken. */
    fun mouseClicked(mouseButton: Int): Boolean = mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT && press(mouseButton)

    /** A press of the confirm key ([KEYBOARD]) or of [mouseButton]: true when it was taken. */
    fun press(mouseButton: Int): Boolean {
        if (latched) return true
        if (length() == null) return false
        latched = true
        button = mouseButton
        hold.press(System.nanoTime())
        if (!complete()) finish() else advance(false)
        BattleUiSounds.click()
        return true
    }

    /** The confirm key came up. Mouse releases have no hook; [tick] reads the button itself. */
    fun keyReleased(keyCode: Int, scanCode: Int) {
        if (button == KEYBOARD && CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) release()
    }

    /** Lets go of a held press without stepping it; a release missed meanwhile is caught from the input's state. */
    fun releaseIfUp() {
        if (latched && !down()) release()
    }

    /** Every frame the box is shown: the held press's repeat steps. */
    fun tick() {
        releaseIfUp()
        if (!latched || !hold.due(System.nanoTime()) || length() == null) return
        if (!complete()) {
            finish()
        } else {
            advance(true)
            BattleUiSounds.click()
        }
    }

    fun release() {
        if (!latched) return
        latched = false
        button = KEYBOARD
        hold.release()
        released()
    }

    /** Whether whatever started the hold, the confirm key or a mouse button, is still down. */
    private fun down(): Boolean {
        val window = Minecraft.getInstance().window.window
        if (button != KEYBOARD) return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS
        val key = KeyBindingHelper.getBoundKeyOf(CobblemonUiClient.selectActionKey)
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window, key.value) == GLFW.GLFW_PRESS
            else -> true
        }
    }

    companion object {
        /** One typing speed for every message box. */
        const val CHARACTERS_PER_SECOND = 80.0
        const val HOLD_DELAY_NANOS = 200_000_000L
        const val HOLD_STEP_NANOS = 35_000_000L
        /** [press]'s source for the confirm key. */
        const val KEYBOARD = -1

        /** Characters written out since [startNanos] ([System.nanoTime]). */
        @JvmStatic
        fun charactersSince(startNanos: Long): Int =
            ((System.nanoTime() - startNanos) / 1e9 * CHARACTERS_PER_SECOND).toInt()

        /** How long [characters] take to write out. */
        @JvmStatic
        fun millisFor(characters: Int): Long = (characters * 1000.0 / CHARACTERS_PER_SECOND).toLong()
    }
}
