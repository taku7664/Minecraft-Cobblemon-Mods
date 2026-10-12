package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.NpcDialogue
import jbro.cobblemon.uikit.UiButtonVariant
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** An explicit save/discard/cancel decision when leaving a draft. */
class NpcUnsavedScreen(private val parent: Screen, private val save: () -> Unit, private val discard: () -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.unsaved")) {
    override fun init() {
        super.init()
        val w = minOf(340, width - 16)
        val x = (width - w) / 2
        val y = (height - 96) / 2
        panel(x, y, w, 96, title)
        label(Component.translatable("screen.cobblemon_npc.close_hint"), x + 12, y + 30, w - 24)
        val each = (w - 32) / 3
        button(x + 12, y + 58, Component.translatable("screen.cobblemon_npc.save_close"), UiButtonVariant.PRIMARY, each) {
            minecraft!!.setScreen(parent); save()
        }
        button(x + 16 + each, y + 58, Component.translatable("screen.cobblemon_npc.discard"), UiButtonVariant.DANGER, each) { discard() }
        button(x + 20 + each * 2, y + 58, Component.translatable("gui.cancel"), width = each) { onClose() }
    }
    override fun onClose() { minecraft?.setScreen(parent) }
}

/** Search both node IDs and dialogue text; a null target explicitly ends the conversation. */
class NpcNodePickerScreen(private val parent: Screen, private val dialogue: NpcDialogue,
                          private val allowEnd: Boolean = true, private val pick: (String?) -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.choose_target")) {
    private var query = ""
    private var offset = 0
    private val rows = mutableListOf<Button>()
    override fun init() {
        super.init()
        rows.clear()
        panel(8, 8, width - 16, height - 16, title)
        val search = field(18, 32, width - 36, query, Component.translatable("screen.cobblemon_npc.hint.search"), 64)
        search.setResponder { query = it; offset = 0; refreshRows() }
        button(width - 98, height - 44, Component.translatable("gui.cancel"), width = 80) { onClose() }
        refreshRows()
        setInitialFocus(search)
    }
    private fun refreshRows() {
        rows.forEach(::removeWidget); rows.clear()
        val matches: List<String?> = (if (allowEnd) listOf<String?>(null) else emptyList()) + dialogue.nodes.keys
        val filtered = matches.filter { id ->
            query.isBlank() || (id == null && Component.translatable("screen.cobblemon_npc.end").string.contains(query, true)) ||
                (id != null && (id.contains(query, true) || dialogue.nodes.getValue(id).lines.any { it.contains(query, true) }))
        }
        val visible = ((height - 112) / 22).coerceAtLeast(1)
        offset = offset.coerceIn(0, (filtered.size - visible).coerceAtLeast(0))
        filtered.drop(offset).take(visible).forEachIndexed { index, id ->
            val text = if (id == null) Component.translatable("screen.cobblemon_npc.end")
            else Component.literal("${if (id == dialogue.start) "★ " else ""}$id — ${dialogue.nodes.getValue(id).lines.firstOrNull().orEmpty().replace('\n', ' ')}")
            rows += button(18, 60 + index * 22, text, width = width - 36) { pick(id); onClose() }.also { it.setTooltip(Tooltip.create(text)) }
        }
        rows += button(18, height - 44, Component.literal("◀"), width = 36) { offset -= visible; refreshRows() }.also { it.active = offset > 0 }
        rows += button(58, height - 44, Component.literal("▶"), width = 36) { offset += visible; refreshRows() }.also { it.active = offset + visible < filtered.size }
        status = if (filtered.isEmpty()) Component.translatable("screen.cobblemon_npc.no_matches") else null
    }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseY >= 60 && mouseY < height - 50 && scrollY != 0.0) {
            offset -= if (scrollY > 0) 3 else -3
            refreshRows(); return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
    override fun onClose() { minecraft?.setScreen(parent) }
}
