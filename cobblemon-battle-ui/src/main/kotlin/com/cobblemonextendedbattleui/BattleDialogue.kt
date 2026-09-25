package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.dialogue.BattleDialogueQueue
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/** Short, acknowledged battle narration drawn over the command area. */
object BattleDialogue {
    private val queue = BattleDialogueQueue<Text>()

    fun enqueue(messages: List<Text>) {
        queue.enqueue(messages.filter { it.string.isNotBlank() })
    }

    fun hasPending(): Boolean = queue.hasPending()

    fun confirm(keyCode: Int, scanCode: Int): Boolean {
        if (!CobblemonExtendedBattleUIClient.selectActionKey.matchesKey(keyCode, scanCode)) return false
        if (!queue.hasPending() && !queue.isConfirmHeld) return false
        queue.pressConfirm()
        return true
    }

    fun releaseConfirm(keyCode: Int, scanCode: Int) {
        if (CobblemonExtendedBattleUIClient.selectActionKey.matchesKey(keyCode, scanCode)) {
            queue.releaseConfirm()
        }
    }

    fun clear() = queue.clear()

    fun render(context: DrawContext) {
        val message = queue.current() ?: return
        val battle = CobblemonClient.battle ?: return
        val client = MinecraftClient.getInstance()
        if (battle.minimised || client.options.hudHidden || BattleInfoPanel.isExpanded) return

        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight
        val width = minOf(440, screenWidth - 24).coerceAtLeast(40)
        val left = (screenWidth - width) / 2
        val lines = client.textRenderer.wrapLines(Text.literal(message.string), width - 20)
        val lineHeight = client.textRenderer.fontHeight + 2
        val height = 18 + lines.size * lineHeight
        val top = (screenHeight - height - 10).coerceAtLeast(4)

        context.fill(left, top, left + width, top + height, 0xC0000000.toInt())
        lines.forEachIndexed { index, line ->
            context.drawText(client.textRenderer, line, left + 10, top + 9 + index * lineHeight, 0xFFFFFFFF.toInt(), false)
        }
    }
}
