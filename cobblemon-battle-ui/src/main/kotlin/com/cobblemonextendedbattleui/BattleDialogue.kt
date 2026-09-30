package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.dialogue.BattleDialogueQueue
import jbro.cobblemon.battleui.extended.battle.messages.TranslationKeys
import jbro.cobblemon.battleui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.battleui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurface
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.battleui.extended.ui.shared.BattleUiTheme
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent

/**
 * Short, acknowledged battle narration drawn over the command area, in the games' message-box manner: text is
 * written out quickly, the confirm key first finishes the line and then moves on, and a bobbing arrow shows when
 * the line is complete.
 */
object BattleDialogue {
    private val queue = BattleDialogueQueue<Text>()
    private const val CHARACTERS_PER_SECOND = 75.0

    private var revealing: Any? = null
    private var revealNanos = 0L
    private var revealHeld = false

    fun enqueue(messages: List<Text>) {
        queue.enqueue(messages.filter { message ->
            message.string.isNotBlank() &&
                (message.content as? TranslatableTextContent)?.key != TranslationKeys.TURN_KEY
        })
    }

    fun hasPending(): Boolean = queue.hasPending()

    fun confirm(keyCode: Int, scanCode: Int): Boolean {
        if (!CobblemonExtendedBattleUIClient.selectActionKey.matchesKey(keyCode, scanCode)) return false
        if (!queue.hasPending() && !queue.isConfirmHeld && !revealHeld) return false
        // A press that finishes the line is held until release, so key repeat cannot also skip it.
        if (revealHeld) return true
        val current = queue.current()
        if (current != null && !queue.isConfirmHeld && revealed(current) < current.string.length) {
            revealNanos = 0L
            revealHeld = true
            return true
        }
        queue.pressConfirm()
        return true
    }

    fun releaseConfirm(keyCode: Int, scanCode: Int) {
        if (CobblemonExtendedBattleUIClient.selectActionKey.matchesKey(keyCode, scanCode)) {
            queue.releaseConfirm()
            revealHeld = false
        }
    }

    /** How many characters of [message] are written out so far; a new message starts from none. */
    private fun revealed(message: Text): Int {
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

    fun render(context: DrawContext) {
        val message = queue.current() ?: return
        val battle = CobblemonClient.battle ?: return
        val client = MinecraftClient.getInstance()
        if (battle.minimised || client.options.hudHidden || BattleInfoPanel.isExpanded ||
            jbro.cobblemon.battleui.extended.ui.transcript.BattleTranscriptOverlay.isOpen) return
        renderMessage(context, message)
    }

    internal fun renderMessage(context: DrawContext, message: Text) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight
        val width = minOf(400, screenWidth - 24).coerceAtLeast(40)
        val left = (screenWidth - width) / 2
        val padding = 12
        val lines = font.textHandler.wrapLines(message.string, width - padding * 2 - 12, Style.EMPTY).map { it.string }
        val lineHeight = font.fontHeight + 3
        val height = (maxOf(2, lines.size) * lineHeight + 17).coerceAtMost(screenHeight - 8)
        // Above the vanilla hotbar and the health and hunger row over it, rather than on them.
        val top = (screenHeight - height - 44).coerceAtLeast(4)
        val corners = BattleCornerCuts(10, 10, 10, 10)

        BattleSurfaceRenderer.draw(context, left, top + 3, width, height, BattleSurface(0x50000000, cornerCuts = corners))
        BattleSurfaceRenderer.draw(context, left, top, width, height,
            BattleUiTheme.shell.copy(border = 0x6669D6E8, borderWidth = 1, cornerCuts = corners, backgroundOpacity = .9f))
        BattleSurfaceRenderer.capsule(context, left + 5, top + 8, 3, height - 16, BattleUiTheme.CYAN, .8f)

        var budget = revealed(message)
        lines.forEachIndexed { index, line ->
            if (budget <= 0) return@forEachIndexed
            val shown = if (budget >= line.length) line else line.substring(0, budget)
            budget -= line.length
            context.drawText(font, shown, left + padding + 2, top + 9 + index * lineHeight, BattleUiTheme.TEXT, false)
        }
        if (budget < 0) return
        // The line is complete: the key to press, and the arrow that says there is more.
        val keyName = CobblemonExtendedBattleUIClient.selectActionKey.boundKeyLocalizedText.string
        val keyWidth = font.getWidth(keyName) + 10
        val arrowX = left + width - 16
        val baseY = top + height - 14
        BattleSurfaceRenderer.capsule(context, arrowX - 6 - keyWidth, baseY - 1, keyWidth, 11, 0xFF1B2C42.toInt())
        context.drawText(font, keyName, arrowX - 6 - keyWidth + 5, baseY + 1, BattleUiTheme.MUTED, false)
        val bob = (BattleFocusMotion.pulse() * 2f).toInt()
        for (row in 0 until 4) {
            context.fill(arrowX + row, baseY + bob + row, arrowX + 7 - row, baseY + bob + row + 1, BattleUiTheme.CYAN)
        }
    }
}
