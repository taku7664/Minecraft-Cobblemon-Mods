package jbro.cobblemon.ui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.ui.dialogue.BattleDialogueQueue
import jbro.cobblemon.ui.extended.battle.messages.TranslationKeys
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
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
 * the line is complete.
 */
object BattleDialogue {
    private val queue = BattleDialogueQueue<Component>()
    private const val CHARACTERS_PER_SECOND = 75.0
    private const val END_BAR = 4

    private var revealing: Any? = null
    private var revealNanos = 0L
    private var revealHeld = false

    fun enqueue(messages: List<Component>) {
        queue.enqueue(messages.filter { message ->
            message.string.isNotBlank() &&
                (message.contents as? TranslatableContents)?.key != TranslationKeys.TURN_KEY
        })
    }

    fun hasPending(): Boolean = queue.hasPending()

    fun confirm(keyCode: Int, scanCode: Int): Boolean {
        if (!CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) return false
        if (!queue.hasPending() && !queue.isConfirmHeld && !revealHeld) return false
        // A press that finishes the line is held until release, so key repeat cannot also skip it.
        if (revealHeld) return true
        val current = queue.current()
        if (current != null && !queue.isConfirmHeld && revealed(current) < current.string.length) {
            revealNanos = 0L
            revealHeld = true
            BattleUiSounds.click()
            return true
        }
        // A held key only waits for its release; the press that moves on clicks.
        if (!queue.isConfirmHeld && current != null) BattleUiSounds.click()
        queue.pressConfirm()
        return true
    }

    fun releaseConfirm(keyCode: Int, scanCode: Int) {
        if (CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) {
            queue.releaseConfirm()
            revealHeld = false
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
    }

    fun render(context: GuiGraphics) {
        val message = queue.current() ?: return
        val battle = CobblemonClient.battle ?: return
        val client = Minecraft.getInstance()
        if (battle.minimised || client.options.hideGui || BattleInfoPanel.isExpanded ||
            jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.isOpen) return
        renderMessage(context, message)
    }

    /**
     * The message box in the shared look ([CobblemonUiSharedTheme]), the window the MCC hub's cards use: its frame,
     * its panel text and that text's shadow, so narration reads like the hub around it.
     */
    internal fun renderMessage(context: GuiGraphics, message: Component) {
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

        UiSurfaceRenderer.draw(context, left, top, width, height, theme.surfaces.panel)
        // A message window, not a menu: Platinum's text windows carry a bar at each end inside the frame.
        val inset = (theme.surfaces.panel.border as? UiBorder.WindowFrame)?.thickness ?: 2
        context.fill(left + inset, top + inset, left + inset + END_BAR, top + height - inset, theme.colors.borderBright)
        context.fill(left + width - inset - END_BAR, top + inset, left + width - inset, top + height - inset, theme.colors.borderBright)
        var budget = revealed(message)
        lines.forEachIndexed { index, line ->
            if (budget <= 0) return@forEachIndexed
            val shown = if (budget >= line.length) line else line.substring(0, budget)
            budget -= line.length
            UiTextRenderer.draw(context, font, Component.literal(shown), left + padding, top + 12 + index * lineHeight, ink, shadow)
        }
        if (budget < 0) return
        // The line is complete: the key to press, and the arrow that says there is more.
        val key = theme.style(UiButtonVariant.SECONDARY, UiWidgetState.NORMAL)
        val keyName = CobblemonUiClient.selectActionKey.translatedKeyMessage
        val keyWidth = font.width(keyName) + 10
        val arrowX = left + width - 20
        val baseY = top + height - 16
        UiSurfaceRenderer.draw(context, arrowX - 6 - keyWidth, baseY - 2, keyWidth, 13, key.surface)
        UiTextRenderer.draw(context, font, keyName, arrowX - 6 - keyWidth + 5, baseY + 1, key.text, key.textShadowColor)
        val bob = (BattleFocusMotion.pulse() * 2f).toInt()
        for (row in 0 until 4) {
            context.fill(arrowX + row, baseY + bob + row, arrowX + 7 - row, baseY + bob + row + 1, theme.colors.accentPrimary)
        }
    }
}
