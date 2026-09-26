package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.UiLayoutPlacement
import net.minecraft.client.gui.components.AbstractWidget

object CobblemonUiLayout {
    fun apply(
        originX: Int,
        originY: Int,
        placements: List<UiLayoutPlacement>,
        widgets: Map<String, AbstractWidget>
    ) {
        placements.forEach { placement ->
            val widget = requireNotNull(widgets[placement.key]) { "Missing widget for layout key ${placement.key}" }
            widget.x = originX + placement.bounds.x
            widget.y = originY + placement.bounds.y
            widget.width = placement.bounds.width
            widget.height = placement.bounds.height
        }
    }
}
