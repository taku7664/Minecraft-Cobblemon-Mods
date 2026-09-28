package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiLayoutResult
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.gui.components.AbstractWidget

object CobblemonUiLayout {
    /** Moves and sizes every widget of [widgets] to the rectangle [result] gives its key. */
    fun apply(result: UiLayoutResult, widgets: Map<String, AbstractWidget>) {
        widgets.forEach { (key, widget) -> place(widget, result[key]) }
    }

    fun place(widget: AbstractWidget, rect: UiRect) {
        widget.x = rect.x
        widget.y = rect.y
        widget.width = rect.width
        widget.height = rect.height
    }

    /**
     * Lays already created [widgets] out left to right from [left], [top], wrapping inside [width], and returns
     * where each one landed. Widgets keep the size they were created at.
     */
    fun flow(left: Int, top: Int, width: Int, widgets: List<AbstractWidget>, horizontalGap: Int, verticalGap: Int): List<UiRect> {
        val keys = UiLayout.keys("widget", widgets.size)
        val result = UiLayout.flow(widgets.mapIndexed { index, widget -> UiLayout.leaf(keys[index], widget.width, widget.height) },
            horizontalGap, verticalGap).solve(UiRect(left, top, width, 0))
        return widgets.mapIndexed { index, widget -> result[keys[index]].also { place(widget, it) } }
    }
}
