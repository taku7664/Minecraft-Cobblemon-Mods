package jbro.cobblemon.npc.client

import jbro.cobblemon.uikit.UiButtonVariant
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Select a server-provided dialogue without losing the parent's unsaved fields. */
class DialoguePickerScreen(private val parent: Screen, ids: List<String>, current: String,
                           private val pick: (String) -> Unit) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.dialogue_picker")) {
    private val ids = ids.distinct().sorted()
    private var query = ""
    private var offset = 0
    private var selected = current.takeIf { it in ids }
    private val rows = mutableListOf<AbstractWidget>()
    private var choose: AbstractWidget? = null
    private var listWidth = 0

    override fun init() {
        super.init()
        panel(8, 8, width - 16, height - 16, title)
        listWidth = width - 36
        val search = field(18, 32, listWidth, query, Component.translatable("screen.cobblemon_npc.hint.search"), 64)
        search.setResponder { query = it; offset = 0; refreshRows() }
        choose = button(width - 184, height - 38, Component.translatable("screen.cobblemon_npc.pick"),
            UiButtonVariant.PRIMARY, 80) { selected?.let(pick); onClose() }
        button(width - 98, height - 38, Component.translatable("gui.cancel"), UiButtonVariant.GHOST, 80) { onClose() }
        refreshRows()
        setInitialFocus(search)
    }

    private fun refreshRows() {
        rows.forEach(::removeWidget)
        rows.clear()
        val matches = ids.filter { it.contains(query.trim(), ignoreCase = true) }
        val visible = ((height - 102) / 21).coerceAtLeast(1)
        offset = offset.coerceIn(0, (matches.size - visible).coerceAtLeast(0))
        matches.drop(offset).take(visible).forEachIndexed { index, id ->
            rows += button(18, 56 + index * 21, Component.literal(id),
                if (selected == id) UiButtonVariant.PRIMARY else UiButtonVariant.GHOST, listWidth) {
                selected = id
                refreshRows()
            }
        }
        choose?.active = selected != null
        status = if (matches.isEmpty()) Component.translatable("screen.cobblemon_npc.no_matches") else null
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseY in 56.0..(height - 46.0) && scrollY != 0.0) {
            offset -= scrollY.toInt().coerceIn(-1, 1) * 3
            refreshRows()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() { minecraft?.setScreen(parent) }
}
