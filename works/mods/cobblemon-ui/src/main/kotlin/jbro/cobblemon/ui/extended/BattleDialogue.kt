package jbro.cobblemon.ui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.ui.dialogue.BattleDialogueQueue
import jbro.cobblemon.ui.extended.battle.messages.TranslationKeys
import jbro.cobblemon.ui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.ui.extended.ui.shared.BattleDialogueStyle
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.ui.extended.ui.shared.BattleSurface
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.client.UiDialogueStyle
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents

/**
 * Short, acknowledged battle narration drawn over the command area, in the games' message-box manner: text is
 * written out quickly, the confirm key first finishes the line and then moves on, and a bobbing arrow shows when
 * the line is complete. Spectators cannot hold the battle up, so for them the box follows the latest lines on its
 * own.
 */
object BattleDialogue {
    private val queue = BattleDialogueQueue<Component>()
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
    // Typing and the confirm key/click/hold, as every message box has them. A press that moves on latches the queue,
    // so the held key that ends the narration does not reach the menu that follows it.
    private val advance = DialogueAdvance(
        length = { queue.current()?.let { revealing(it); it.string.length } },
        advance = { held -> if (held && queue.isConfirmHeld) queue.repeatConfirm() else queue.pressConfirm() },
        released = { queue.releaseConfirm() },
    )
    private var completeNanos = 0L

    fun enqueue(messages: List<Component>) {
        val shown = messages.filter { message ->
            message.string.isNotBlank() &&
                (message.contents as? TranslatableContents)?.key != TranslationKeys.TURN_KEY
        }
        if (shown.isEmpty()) return
        // A key held on from the last menu is not for the next one: the turn's lines come between them.
        BattleConfirmGate.released()
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
        if (SceneDialogue.isActive()) return false
        return advance.keyPressed(keyCode, scanCode)
    }

    /** A left click on the battle screen does what the confirm key does, holding included. */
    fun click(button: Int): Boolean {
        if (!queue.hasPending() || SceneDialogue.isActive()) return false
        return advance.mouseClicked(button)
    }

    fun releaseConfirm(keyCode: Int, scanCode: Int) = advance.keyReleased(keyCode, scanCode)

    /** Starts [message]'s typing over when it is not the line already being written out. */
    private fun revealing(message: Component) {
        if (revealing === message) return
        revealing = message
        advance.restart()
    }

    /** How many characters of [message] are written out so far; a new message starts from none. */
    private fun revealed(message: Component): Int {
        revealing(message)
        return advance.shown()
    }

    fun clear() {
        advance.release()
        queue.clear()
        revealing = null
        completeNanos = 0L
    }

    fun render(context: GuiGraphics) {
        // Checked with nothing to say too, so a release missed meanwhile cannot eat the next press in the menus.
        advance.releaseIfUp()
        val message = queue.current() ?: return
        // A trainer speaking mid-turn (a Dynamax) has the floor; the narration waits under it.
        if (SceneDialogue.isActive()) return
        val battle = CobblemonClient.battle ?: return
        val client = Minecraft.getInstance()
        if (battle.minimised || client.options.hideGui || BattleInfoPanel.isExpanded ||
            jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.isOpen) return
        advance.tick()
        tickSpectator()
        renderMessage(context, queue.current() ?: return)
    }

    /**
     * The message box in [UiDialogueStyle]'s look, a card of the MCC hub's content theme: its frame, its text and
     * the bars at its ends, so narration reads like the hub around it. [shown] characters are written
     * out; the box's rectangle is returned so a caller can set things against it.
     */
    internal fun renderMessage(context: GuiGraphics, message: Component, shown: Int = revealed(message)): UiRect {
        val client = Minecraft.getInstance()
        val font = client.font
        val theme = UiDialogueStyle.theme()
        val ink = UiDialogueStyle.ink(theme)
        val shadow = UiDialogueStyle.inkShadow(theme)
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
        // Above the cinematic letterbox's bottom bar while a scene plays, never on it.
        val top = (screenHeight - height - 6 - CinematicLetterbox.bottomInset(screenHeight)).coerceAtLeast(4)

        val band = BattleUiTheme.palette.dialogue == BattleDialogueStyle.DARK_BAND
        val textInk = if (band) BAND_TEXT else ink
        val textShadow = if (band) BAND_SHADOW else shadow
        if (band) {
            // Sword and Shield narrate on a dark translucent band, its corners cut on one diagonal.
            BattleSurfaceRenderer.draw(context, left, top, width, height, BattleSurface(BAND_TOP, BAND_BOTTOM,
                cornerCuts = BattleCornerCuts(topLeft = BAND_CUT, bottomRight = BAND_CUT), rounded = false))
            context.fill(left + BAND_CUT, top + 3, left + width - 3, top + 4, BAND_RULE)
        } else {
            // A message window, not a menu: Platinum's text windows carry a bar at each end inside the frame.
            UiDialogueStyle.drawBox(context, theme, UiRect(left, top, width, height))
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
        val arrow = if (band) BAND_TEXT else UiDialogueStyle.arrow(theme)
        val bob = (BattleFocusMotion.pulse() * 2f).toInt()
        UiDialogueStyle.drawArrow(context, theme, arrowX, baseY + bob, arrow)
        return box
    }
}
