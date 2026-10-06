package jbro.cobblemon.ui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import com.mojang.blaze3d.platform.InputConstants
import jbro.cobblemon.ui.dialogue.BattleDialogueQueue
import jbro.cobblemon.ui.dialogue.HoldRepeat
import jbro.cobblemon.ui.extended.battle.messages.TranslationKeys
import jbro.cobblemon.ui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.ui.extended.ui.shared.BattleDialogueStyle
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.ui.extended.ui.shared.BattleSurface
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import org.lwjgl.glfw.GLFW

/**
 * Short, acknowledged battle narration drawn over the command area, in the games' message-box manner: text is
 * written out quickly, the confirm key first finishes the line and then moves on, and a bobbing arrow shows when
 * the line is complete. Spectators cannot hold the battle up, so for them the box follows the latest lines on its
 * own.
 */
object BattleDialogue {
    private val queue = BattleDialogueQueue<Component>()
    private const val CHARACTERS_PER_SECOND = 75.0
    private const val END_BAR = 4
    private const val BAND_CUT = 10
    private const val BAND_TOP = 0xE6262626.toInt()
    private const val BAND_BOTTOM = 0xE6161616.toInt()
    private const val BAND_RULE = 0x33FFFFFF
    private const val BAND_CHIP = 0x33FFFFFF
    private const val BAND_TEXT = 0xFFFFFFFF.toInt()
    private const val BAND_SHADOW = 0xFF000000.toInt()
    // How long a spectator's finished line stays before the next one.
    private const val SPECTATOR_DWELL_NANOS = 1_200_000_000L

    private var revealing: Any? = null
    private var revealNanos = 0L
    private var revealHeld = false
    // Paced like a keyboard's own repeat: a pause long enough for a single tap, then a quick run.
    private val hold = HoldRepeat(400_000_000L, 70_000_000L)
    private var completeNanos = 0L
    private const val NO_MOUSE_BUTTON = -1
    private var heldMouseButton = NO_MOUSE_BUTTON

    fun enqueue(messages: List<Component>) {
        val shown = messages.filter { message ->
            message.string.isNotBlank() &&
                (message.contents as? TranslatableContents)?.key != TranslationKeys.TURN_KEY
        }
        if (shown.isEmpty()) return
        // A spectator is never waited for, so lines still unread give way to the newest ones.
        if (isSpectating()) queue.replace(shown) else queue.enqueue(shown)
    }

    private fun isSpectating(): Boolean {
        val battle = CobblemonClient.battle ?: return false
        val player = Minecraft.getInstance().player?.uuid ?: return false
        return battle.side1.actors.none { it.uuid == player } && battle.side2.actors.none { it.uuid == player }
    }

    /** A spectator's line moves on by itself once it has been written out and read for a moment. */
    private fun tickSpectator() {
        if (!isSpectating()) return
        val current = queue.current() ?: return
        if (revealed(current) < current.string.length) {
            completeNanos = 0L
            return
        }
        val now = System.nanoTime()
        if (completeNanos == 0L) completeNanos = now
        if (now - completeNanos < SPECTATOR_DWELL_NANOS) return
        completeNanos = 0L
        queue.advance()
    }

    fun hasPending(): Boolean = queue.hasPending()

    fun confirm(keyCode: Int, scanCode: Int): Boolean {
        if (!CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) return false
        return press(NO_MOUSE_BUTTON)
    }

    /** A left click on the battle screen does what the confirm key does, holding included. */
    fun click(button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !queue.hasPending()) return false
        return press(button)
    }

    private fun press(mouseButton: Int): Boolean {
        if (!queue.hasPending() && !queue.isConfirmHeld && !revealHeld) return false
        // A press that finishes the line is held until release, so key repeat cannot also skip it.
        // Holding the key repeats on its own timer (see [tickHold]), not the system's key repeat.
        if (revealHeld) return true
        val current = queue.current()
        if (current != null && !queue.isConfirmHeld && revealed(current) < current.string.length) {
            revealNanos = 0L
            revealHeld = true
            holdFrom(mouseButton)
            BattleUiSounds.click()
            return true
        }
        // A held key only waits for its release; the press that moves on clicks.
        if (!queue.isConfirmHeld && current != null) {
            BattleUiSounds.click()
            holdFrom(mouseButton)
        }
        queue.pressConfirm()
        return true
    }

    private fun holdFrom(mouseButton: Int) {
        heldMouseButton = mouseButton
        hold.press(System.nanoTime())
    }

    fun releaseConfirm(keyCode: Int, scanCode: Int) {
        if (heldMouseButton == NO_MOUSE_BUTTON && CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) {
            releaseHold()
        }
    }

    private fun releaseHold() {
        queue.releaseConfirm()
        revealHeld = false
        heldMouseButton = NO_MOUSE_BUTTON
        hold.release()
    }

    /**
     * Held confirm runs on like a keyboard's key repeat: after a short pause each step finishes the line being
     * written, or moves on when it is already complete.
     */
    private fun tickHold() {
        if (!hold.isHeld()) return
        if (!confirmKeyDown()) {
            // The release can land while another screen has the keyboard; the key's own state settles it.
            releaseHold()
            return
        }
        if (!hold.due(System.nanoTime())) return
        val current = queue.current() ?: return
        if (revealed(current) < current.string.length) {
            revealNanos = 0L
            return
        }
        // Keep the latch on, so the eventual release is still the one that frees the key.
        if (!queue.isConfirmHeld) queue.pressConfirm() else queue.repeatConfirm()
        revealHeld = true
        BattleUiSounds.click()
    }

    /** Whether whatever started the hold, the confirm key or a mouse button, is still down. */
    private fun confirmKeyDown(): Boolean {
        val window = Minecraft.getInstance().window.window
        // No hook watches mouse releases; the button's own state stands in for one.
        if (heldMouseButton != NO_MOUSE_BUTTON) return GLFW.glfwGetMouseButton(window, heldMouseButton) == GLFW.GLFW_PRESS
        val key = KeyBindingHelper.getBoundKeyOf(CobblemonUiClient.selectActionKey)
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window, key.value) == GLFW.GLFW_PRESS
            else -> true
        }
    }

    /** How many characters of [message] are written out so far; a new message starts from none. */
    private fun revealed(message: Component): Int {
        if (revealing !== message) {
            revealing = message
            revealNanos = System.nanoTime()
        }
        if (revealNanos == 0L) return Int.MAX_VALUE
        return ((System.nanoTime() - revealNanos) / 1e9 * CHARACTERS_PER_SECOND).toInt()
    }

    fun clear() {
        queue.clear()
        revealing = null
        revealHeld = false
        completeNanos = 0L
        heldMouseButton = NO_MOUSE_BUTTON
        hold.release()
    }

    fun render(context: GuiGraphics) {
        // Checked with nothing to say too, so a release missed meanwhile cannot eat the next press in the menus.
        if (hold.isHeld() && !confirmKeyDown()) releaseHold()
        val message = queue.current() ?: return
        val battle = CobblemonClient.battle ?: return
        val client = Minecraft.getInstance()
        if (battle.minimised || client.options.hideGui || BattleInfoPanel.isExpanded ||
            jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.isOpen) return
        tickHold()
        tickSpectator()
        renderMessage(context, queue.current() ?: return)
    }

    /**
     * The message box in the shared look ([CobblemonUiSharedTheme]), the window the MCC hub's cards use: its frame,
     * its panel text and that text's shadow, so narration reads like the hub around it. [shown] characters are written
     * out; the box's rectangle is returned so a caller can set things against it.
     */
    internal fun renderMessage(context: GuiGraphics, message: Component, shown: Int = revealed(message)): UiRect {
        val client = Minecraft.getInstance()
        val font = client.font
        val theme = CobblemonUiSharedTheme.snapshot()
        val ink = theme.surfaces.panelText ?: theme.colors.textPrimary
        val shadow = theme.listRowStyle(UiWidgetState.NORMAL).textShadowColor
        val screenWidth = client.window.guiScaledWidth
        val screenHeight = client.window.guiScaledHeight
        // A message box with room on both sides, not edge to edge.
        val width = (screenWidth * 0.73f).toInt().coerceIn(minOf(280, screenWidth - 24), 420)
        val left = (screenWidth - width) / 2
        val padding = 14
        val lines = font.splitter.splitLines(message.string, width - padding * 2 - 12, Style.EMPTY).map { it.string }
        val lineHeight = font.lineHeight + 3
        // Room for three lines at least, as the games' message box has.
        val height = (maxOf(3, lines.size) * lineHeight + 22).coerceAtMost(screenHeight - 8)
        // At the bottom of the screen like the games' message box; it may cover the hotbar while it speaks.
        val top = (screenHeight - height - 6).coerceAtLeast(4)

        val band = BattleUiTheme.palette.dialogue == BattleDialogueStyle.DARK_BAND
        val textInk = if (band) BAND_TEXT else ink
        val textShadow = if (band) BAND_SHADOW else shadow
        if (band) {
            // Sword and Shield narrate on a dark translucent band, its corners cut on one diagonal.
            BattleSurfaceRenderer.draw(context, left, top, width, height, BattleSurface(BAND_TOP, BAND_BOTTOM,
                cornerCuts = BattleCornerCuts(topLeft = BAND_CUT, bottomRight = BAND_CUT), rounded = false))
            context.fill(left + BAND_CUT, top + 3, left + width - 3, top + 4, BAND_RULE)
        } else {
            UiSurfaceRenderer.draw(context, left, top, width, height, theme.surfaces.panel)
            // A message window, not a menu: Platinum's text windows carry a bar at each end inside the frame.
            val inset = (theme.surfaces.panel.border as? UiBorder.WindowFrame)?.thickness ?: 2
            context.fill(left + inset, top + inset, left + inset + END_BAR, top + height - inset, theme.colors.borderBright)
            context.fill(left + width - inset - END_BAR, top + inset, left + width - inset, top + height - inset, theme.colors.borderBright)
        }
        val box = UiRect(left, top, width, height)
        var budget = shown
        lines.forEachIndexed { index, line ->
            if (budget <= 0) return@forEachIndexed
            val shown = if (budget >= line.length) line else line.substring(0, budget)
            budget -= line.length
            UiTextRenderer.draw(context, font, Component.literal(shown), left + padding, top + 12 + index * lineHeight, textInk, textShadow)
        }
        if (budget < 0) return box
        // The line is complete: the key to press, and the arrow that says there is more.
        val key = theme.style(UiButtonVariant.SECONDARY, UiWidgetState.NORMAL)
        val keyName = CobblemonUiClient.selectActionKey.translatedKeyMessage
        val keyWidth = font.width(keyName) + 10
        val arrowX = left + width - 20
        val baseY = top + height - 16
        if (band) {
            val chip = BattleCornerCuts(6, 6, 6, 6)
            BattleSurfaceRenderer.draw(context, arrowX - 6 - keyWidth, baseY - 2, keyWidth, 13,
                BattleSurface(BAND_CHIP, BAND_CHIP, cornerCuts = chip))
            UiTextRenderer.draw(context, font, keyName, arrowX - 6 - keyWidth + 5, baseY + 1, BAND_TEXT, BAND_SHADOW)
        } else {
            UiSurfaceRenderer.draw(context, arrowX - 6 - keyWidth, baseY - 2, keyWidth, 13, key.surface)
            UiTextRenderer.draw(context, font, keyName, arrowX - 6 - keyWidth + 5, baseY + 1, key.text, key.textShadowColor)
        }
        val arrow = if (band) BAND_TEXT else theme.colors.accentPrimary
        val bob = (BattleFocusMotion.pulse() * 2f).toInt()
        for (row in 0 until 4) {
            context.fill(arrowX + row, baseY + bob + row, arrowX + 7 - row, baseY + bob + row + 1, arrow)
        }
        return box
    }
}
