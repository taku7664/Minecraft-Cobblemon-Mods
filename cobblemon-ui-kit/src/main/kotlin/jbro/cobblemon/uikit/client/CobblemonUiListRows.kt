package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiCross
import jbro.cobblemon.uikit.UiInsets
import jbro.cobblemon.uikit.UiJustify
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/** A small control at the end of a list row, such as a quantity step. */
data class UiListRowAction(val label: Component, val enabled: Boolean = true)

/**
 * What one list row shows: an [icon] drawn by the render slot, a [title] with an optional [supporting] line,
 * [trailing] text, and [actions] at its end.
 */
data class UiListRowContent(
    val title: Component,
    val supporting: Component? = null,
    val trailing: Component? = null,
    val selected: Boolean = false,
    val icon: CobblemonUiRenderContent? = null,
    val actions: List<UiListRowAction> = emptyList()
)

/**
 * Draws list rows in the theme's list row style ([jbro.cobblemon.uikit.UiThemeSnapshot.listRowStyle]), so a row
 * reads the same wherever a screen places it: a framed button in the pixel themes, or an unframed DS menu line
 * marked by the cursor. Screens own the widget and its input; [actionBounds] tells which action a click hit.
 */
object CobblemonUiListRows {
    /** Where each of [count] actions sits at the end of a row at [bounds]. */
    fun actionBounds(bounds: UiRect, count: Int): List<UiRect> {
        if (count == 0) return emptyList()
        val size = (bounds.height - 6).coerceAtMost(18)
        val keys = UiLayout.keys("action", count)
        return UiLayout.row(gap = 2, padding = UiInsets(0, 0, 4, 0), justify = UiJustify.END) {
            keys.forEach { fixed(size, it, UiCross.centered(size)) }
        }.solve(bounds).list("action")
    }

    fun draw(
        graphics: GuiGraphics,
        bounds: UiRect,
        row: UiListRowContent,
        enabled: Boolean,
        hovered: Boolean,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        val state = when {
            !enabled -> UiWidgetState.DISABLED
            hovered -> UiWidgetState.HOVER
            row.selected -> UiWidgetState.SELECTED
            else -> UiWidgetState.NORMAL
        }
        val style = theme.listRowStyle(state)
        UiSurfaceRenderer.draw(graphics, bounds.x, bounds.y, bounds.width, bounds.height, style.surface)
        var left = bounds.x + 6
        row.icon?.let { icon ->
            val size = (bounds.height - 6).coerceAtMost(16)
            CobblemonUiRenderSlot.drawContent(graphics, UiRect(left, bounds.y + (bounds.height - size) / 2, size, size), icon, partialTick)
            left += size + 5
        }
        val actions = actionBounds(bounds, row.actions.size)
        var right = (actions.firstOrNull()?.x ?: (bounds.right - 2)) - 4
        actions.forEachIndexed { index, action ->
            val control = row.actions[index]
            val actionState = when {
                !enabled || !control.enabled -> UiWidgetState.DISABLED
                mouseX >= action.x && mouseX < action.right && mouseY >= action.y && mouseY < action.bottom -> UiWidgetState.HOVER
                else -> UiWidgetState.NORMAL
            }
            val actionStyle = theme.style(UiButtonVariant.SECONDARY, actionState)
            UiSurfaceRenderer.draw(graphics, action.x, action.y, action.width, action.height, actionStyle.surface)
            UiTextRenderer.draw(graphics, font, control.label, action.x + (action.width - font.width(control.label) + 1) / 2,
                action.y + (action.height - font.lineHeight) / 2 + 1, actionStyle.text, actionStyle.textShadowColor)
        }
        row.trailing?.let { trailing ->
            val width = font.width(trailing)
            UiTextRenderer.draw(graphics, font, trailing, right - width, bounds.y + (bounds.height - font.lineHeight) / 2 + 1,
                style.supportingText, style.textShadowColor)
            right -= width + 6
        }
        val room = right - left
        if (row.supporting == null) {
            UiTextRenderer.draw(graphics, font, fitted(row.title, room), left, bounds.y + (bounds.height - font.lineHeight) / 2 + 1,
                style.text, style.textShadowColor)
        } else {
            UiTextRenderer.draw(graphics, font, fitted(row.title, room), left, bounds.y + 4, style.text, style.textShadowColor)
            UiTextRenderer.draw(graphics, font, fitted(row.supporting, room), left, bounds.bottom - font.lineHeight - 3,
                style.supportingText, style.textShadowColor)
        }
        UiSurfaceRenderer.drawSelection(graphics, bounds.x, bounds.y, bounds.width, bounds.height, style.surface.shape,
            UiSurfaceRenderer.indicatorFor(style.selectionIndicator,
                theme.listRowStyle(UiWidgetState.SELECTED).selectionIndicator, row.selected && enabled))
    }

    private fun fitted(text: Component, width: Int): Component {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= width) return text
        return Component.literal(font.plainSubstrByWidth(text.string, (width - font.width("…")).coerceAtLeast(0)) + "…")
    }
}
