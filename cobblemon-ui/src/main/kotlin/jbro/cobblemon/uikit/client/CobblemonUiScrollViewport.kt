package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiScrollState
import jbro.cobblemon.uikit.UiVerticalRange
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import org.lwjgl.glfw.GLFW

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
    private var draggingScrollbar = false
    private var lastFocusedWidget: AbstractWidget? = null

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
        revealFocusedWidget()
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

    fun keyPressed(keyCode: Int): Boolean {
        val changed = when (keyCode) {
            GLFW.GLFW_KEY_PAGE_UP -> state.page(-1)
            GLFW.GLFW_KEY_PAGE_DOWN -> state.page(1)
            GLFW.GLFW_KEY_HOME -> state.home()
            GLFW.GLFW_KEY_END -> state.end()
            else -> false
        }
        if (changed) updateWidgetPositions()
        return changed
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || state.maxOffset <= 0) return false
        if (mouseX < scrollbarLeft() - 2 || mouseX >= left + width || mouseY < trackTop() || mouseY >= trackTop() + trackHeight()) return false
        draggingScrollbar = true
        dragScrollbarTo(mouseY)
        return true
    }

    fun mouseDragged(mouseY: Double, button: Int): Boolean {
        if (!draggingScrollbar || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        dragScrollbarTo(mouseY)
        return true
    }

    fun mouseReleased(button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !draggingScrollbar) return false
        draggingScrollbar = false
        return true
    }

    fun ensureWidgetVisible(widget: AbstractWidget): Boolean {
        val entry = entries.firstOrNull { it.widget === widget } ?: return false
        val changed = state.ensureVisible(UiVerticalRange(entry.contentY, entry.contentY + widget.height))
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
        val thumb = state.thumb(trackTop(), trackHeight(), minimumHeight = 18)
        graphics.fill(scrollbarLeft(), trackTop(), scrollbarLeft() + 2, trackTop() + trackHeight(), theme.colors.panelAlt)
        graphics.fill(scrollbarLeft(), thumb.start, scrollbarLeft() + 2, thumb.endExclusive, theme.colors.accentPrimary)
    }

    private fun revealFocusedWidget() {
        val focused = entries.firstOrNull { it.widget.isFocused }
        if (focused?.widget === lastFocusedWidget) return
        lastFocusedWidget = focused?.widget
        if (focused == null) return
        val changed = state.ensureVisible(UiVerticalRange(focused.contentY, focused.contentY + focused.widget.height))
        if (changed) updateWidgetPositions()
    }

    private fun dragScrollbarTo(mouseY: Double) {
        val thumb = state.thumb(trackTop(), trackHeight(), minimumHeight = 18)
        val thumbHeight = thumb.endExclusive - thumb.start
        val travel = trackHeight() - thumbHeight
        if (travel <= 0) return
        val thumbTop = (mouseY - thumbHeight / 2.0).toInt().coerceIn(trackTop(), trackTop() + travel)
        state.jumpTo((thumbTop - trackTop()) * state.maxOffset / travel)
        updateWidgetPositions()
    }

    private fun trackTop(): Int = top + 3
    private fun trackHeight(): Int = height - 6
    private fun scrollbarLeft(): Int = left + width - 3
}
