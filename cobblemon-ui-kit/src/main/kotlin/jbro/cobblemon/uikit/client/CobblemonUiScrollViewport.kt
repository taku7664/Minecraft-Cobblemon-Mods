package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiScrollState
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget

class CobblemonUiScrollViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    contentHeight: Int,
    step: Int = 18
) {
    private data class Entry(val widget: AbstractWidget, val contentY: Int)

    private val entries = mutableListOf<Entry>()
    val state = UiScrollState(height, contentHeight, step)

    init {
        require(width > 0) { "Scroll viewport width must be positive" }
    }

    fun register(widget: AbstractWidget, contentY: Int): AbstractWidget {
        require(contentY >= 0) { "Scroll content position must not be negative" }
        entries += Entry(widget, contentY)
        updateWidgetPositions()
        return widget
    }

    fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
        content: (offset: Int) -> Unit = {}
    ) {
        graphics.enableScissor(left, top, left + width, top + height)
        try {
            content(state.offset)
            entries.filterNot { it.widget is CobblemonUiComboBox }.forEach { entry ->
                if (entry.widget.visible) entry.widget.render(graphics, mouseX, mouseY, partialTick)
            }
            entries.filter { it.widget is CobblemonUiComboBox }.forEach { entry ->
                if (entry.widget.visible) entry.widget.render(graphics, mouseX, mouseY, partialTick)
            }
        } finally {
            graphics.disableScissor()
        }
        renderScrollbar(graphics)
    }

    fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseX < left || mouseX >= left + width || mouseY < top || mouseY >= top + height) return false
        val delta = if (scrollY != 0.0) scrollY else scrollX
        val changed = state.scroll(delta)
        if (changed) updateWidgetPositions()
        return changed
    }

    fun updateWidgetPositions() {
        entries.forEach { entry ->
            entry.widget.y = top + entry.contentY - state.offset
            entry.widget.visible = entry.widget.y + entry.widget.height > top && entry.widget.y < top + height
        }
    }

    private fun renderScrollbar(graphics: GuiGraphics) {
        if (state.maxOffset <= 0) return
        val theme = CobblemonUiThemes.registry.snapshot()
        val trackTop = top + 3
        val trackHeight = height - 6
        val thumb = state.thumb(trackTop, trackHeight, minimumHeight = 18)
        val trackLeft = left + width - 3
        graphics.fill(trackLeft, trackTop, trackLeft + 2, trackTop + trackHeight, theme.colors.panelAlt)
        graphics.fill(trackLeft, thumb.start, trackLeft + 2, thumb.endExclusive, theme.colors.accentPrimary)
    }
}
