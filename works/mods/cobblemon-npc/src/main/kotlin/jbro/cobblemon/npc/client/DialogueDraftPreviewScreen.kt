package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.DialoguePreviewCursor
import jbro.cobblemon.npc.dialogue.DialogueText
import jbro.cobblemon.npc.dialogue.NpcDialogue
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.UiDialogueStyle
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Safe draft inspection: no networking, world commands, condition commands, camera or file writes. */
class DialogueDraftPreviewScreen(private val parent: Screen, dialogue: NpcDialogue, private val from: String) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.draft_preview")) {
    private val cursor = DialoguePreviewCursor(dialogue, from)
    private var optionOffset = 0
    private var textOffset = 0
    private var textBottom = 0

    private fun tr(key: String, vararg args: Any) = Component.translatable("screen.cobblemon_npc.$key", *args)
    override fun init() {
        super.init()
        panel(8, 8, width - 16, height - 16, title)
        button(20, 32, tr("from_start"), width = 90) { enter(cursor.dialogue.start) }
        button(114, 32, tr("from_selected"), width = 90) { enter(from) }
        button(width - 100, height - 44, tr("back_editor"), width = 80) { onClose() }
        val node = cursor.node
        if (node == null) { label(tr("preview_ended"), 20, 68); return }
        label(tr("preview_node", cursor.nodeId.orEmpty(), if (node.lines.isEmpty()) 0 else cursor.page + 1, node.lines.size), 20, 58)
        if (cursor.hasMorePages) {
            textBottom = height - 76
            button(20, height - 44, tr("next_page"), width = 90) { cursor.advancePage(); textOffset = 0; rebuildWidgets() }
        } else {
            data class Option(val text: Component, val next: String?, val hint: Component?)
            val options = node.choices.map { Option(Component.translatable(it.text), it.next, it.condition?.let { condition -> tr("preview_condition", condition) }) } +
                node.branches.map { Option(tr("preview_branch", it.condition), it.next, tr("preview_condition", it.condition)) } +
                (if (node.choices.isEmpty()) listOf(Option(tr(if (node.next == null) "end" else "continue"), node.next, null)) else emptyList())
            val visible = ((height - 180) / 24).coerceIn(1, 4)
            optionOffset = optionOffset.coerceIn(0, (options.size - visible).coerceAtLeast(0))
            textBottom = height - 72 - minOf(visible, options.size) * 24
            options.drop(optionOffset).take(visible).forEachIndexed { index, option ->
                button(20, textBottom + 6 + index * 24, option.text, width = width - 40) { enter(option.next) }
                    .setTooltip(Tooltip.create(option.hint ?: tr("preview_target", option.next ?: tr("end").string)))
            }
            button(20, height - 44, Component.literal("◀"), width = 28) { optionOffset -= visible; rebuildWidgets() }.active = optionOffset > 0
            button(52, height - 44, Component.literal("▶"), width = 28) { optionOffset += visible; rebuildWidgets() }.active = optionOffset + visible < options.size
        }
        status = tr("preview_safe")
        statusOk = true
    }

    private fun enter(next: String?) { cursor.enter(next); textOffset = 0; optionOffset = 0; rebuildWidgets() }
    private fun text(): String {
        val raw = cursor.node?.lines?.getOrNull(cursor.page).orEmpty()
        val speaker = cursor.dialogue.speaker ?: tr("preview_speaker").string
        return Component.translatable(DialogueText.fill(raw, minecraft?.player?.name?.string.orEmpty(), speaker)).string
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        if (cursor.node == null) return
        val bottom = textBottom.coerceAtLeast(100)
        // In the message box players see, so the draft reads as it will in game.
        val theme = UiDialogueStyle.theme()
        UiDialogueStyle.drawBox(graphics, theme, UiRect(20, 74, width - 40, bottom - 74))
        val ink = UiDialogueStyle.ink(theme)
        val lines = font.split(Component.literal(text()), (width - 64).coerceAtLeast(1))
        val visible = ((bottom - 86) / 11).coerceAtLeast(1)
        textOffset = textOffset.coerceIn(0, (lines.size - visible).coerceAtLeast(0))
        graphics.enableScissor(24, 78, width - 24, bottom - 2)
        lines.drop(textOffset).take(visible).forEachIndexed { index, line -> graphics.drawString(font, line, 30, 82 + index * 11, ink, false) }
        graphics.disableScissor()
        if (lines.size > visible) graphics.drawString(font, Component.literal("↕"), width - 34, 80, ink, false)
        if (mouseY in 74..bottom && mouseX in 20..(width - 20)) setTooltipForNextRenderPass(tr("preview_hint"))
    }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseY in 74.0..textBottom.toDouble() && scrollY != 0.0) {
            textOffset -= if (scrollY > 0) 3 else -3; return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
    override fun onClose() { minecraft?.setScreen(parent) }
}
