package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiThemeSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * Browser-style tabs over a page: the tabs sit on a row, and the selected one opens into the page below it, sharing
 * its colour with no rule between them. The others stay dimmer and a step lower. A locked tab still shows, with a
 * lock, so a player learns what is there to open.
 */
object CobblemonUiTabBar {
    /** Height of the tab row; the page starts right below it. */
    const val HEIGHT = 15

    private const val PADDING = 8
    private const val MIN_WIDTH = 44
    private const val LOCK_WIDTH = 9

    class Item(
        val id: String,
        val label: Component,
        val locked: Boolean = false,
        val enabled: Boolean = true,
        val tooltip: Component? = null,
    )

    /** The page's fill: the dark title colour of a pixel theme, the panel colour otherwise. */
    fun pageColor(theme: UiThemeSnapshot): Int = theme.pixelDecorations?.titleBar ?: theme.colors.panel

    /** Text on the page and on the selected tab. */
    fun pageText(theme: UiThemeSnapshot): Int =
        if (theme.pixelDecorations != null) theme.colors.textPrimary else theme.surfaces.panelText ?: theme.colors.textPrimary

    private fun edgeColor(theme: UiThemeSnapshot): Int = theme.pixelDecorations?.titleBarShade ?: theme.colors.border

    /** Each tab's width: its label with room around it, and a lock's room when it is locked. */
    fun widths(items: List<Item>): List<Int> {
        val font = Minecraft.getInstance().font
        return items.map { maxOf(MIN_WIDTH, font.width(it.label) + PADDING * 2 + if (it.locked) LOCK_WIDTH else 0) }
    }

    /**
     * Adds the page under [row] down to [pageBottom] and the tabs along [row], left to right, through [add].
     * Pressing an unlocked, enabled tab other than [selectedId] reports it to [select]. Returns the page rectangle.
     */
    fun create(
        add: (AbstractWidget) -> Unit,
        row: UiRect,
        pageBottom: Int,
        items: List<Item>,
        selectedId: String?,
        select: (String) -> Unit,
    ): UiRect {
        val page = UiRect(row.x, row.bottom, row.width, (pageBottom - row.bottom).coerceAtLeast(1))
        var x = row.x
        val tabs = items.zip(widths(items)).map { (item, width) ->
            val rect = UiRect(x, row.y, width.coerceAtMost(row.right - x).coerceAtLeast(1), row.height)
            x += width + 2
            Tab(rect, item, item.id == selectedId, rect.x == row.x, select)
        }
        add(Page(page, tabs.firstOrNull { it.selected }?.let { it.x to it.x + it.width }))
        tabs.forEach(add)
        return page
    }

    private class Page(private val rect: UiRect, private val opening: Pair<Int, Int>?) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            UiPixelBox.draw(graphics, theme, rect.x, rect.y, rect.width, rect.height, edgeColor(theme), pageColor(theme))
            // The selected tab opens into the page: no edge under it.
            opening?.let { (left, right) -> graphics.fill(left + 1, rect.y, right - 1, rect.y + 1, pageColor(theme)) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class Tab(r: UiRect, private val item: Item, val selected: Boolean, private val atPageEdge: Boolean,
        private val select: (String) -> Unit) :
        AbstractButton(r.x, r.y, r.width, r.height, item.label) {
        init {
            active = item.enabled && !item.locked
            item.tooltip?.let { tooltip = Tooltip.create(it) }
        }
        override fun onPress() { if (!selected) select(item.id) }
        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val edge = edgeColor(theme)
            // Rest tabs sit two rows lower than the selected one; every tab stands on the page's top edge.
            val top = if (selected) y else y + 2
            val fill = when {
                selected -> pageColor(theme)
                active && (isHovered || isFocused) -> theme.colors.borderBright
                else -> theme.colors.shell.darker(theme)
            }
            val bottom = y + height
            // Each tab is drawn on past the page's top edge so only its top corners round; the part inside the page
            // is painted back as the page. The selected tab opens into it, the others stop at its edge line.
            val reach = UiPixelBox.shape(theme, width, bottom - top).let { (it as? UiShape.RoundedRectangle)?.radiusPixels ?: 0 } + 2
            UiPixelBox.draw(graphics, theme, x, top, width, bottom - top + reach, edge, fill)
            val page = pageColor(theme)
            graphics.fill(if (atPageEdge) x + 1 else x, bottom + 1, x + width, bottom + reach, page)
            if (selected) {
                graphics.fill(x + 1, bottom, x + width - 1, bottom + 1, page)
                graphics.fill(x + 2, top + 1, x + width - 2, top + 3, theme.colors.accentSecondary)
            } else {
                graphics.fill(x, bottom, x + width, bottom + 1, edge)
            }
            val text = when {
                selected -> pageText(theme)
                active -> theme.colors.textSecondary
                else -> theme.colors.textDim
            }
            val room = width - PADDING * 2 + 4 - if (item.locked) LOCK_WIDTH else 0
            val label = UiTextRenderer.fitted(font, message, room.coerceAtLeast(1))
            val labelWidth = font.width(label) + if (item.locked) LOCK_WIDTH else 0
            var left = x + (width - labelWidth) / 2
            // Centred under the selected tab's accent stripe, on the whole tab otherwise.
            val textY = if (selected) UiTextRenderer.centeredY(font, label, top + 3, bottom - top - 3)
                else UiTextRenderer.centeredY(font, label, top, bottom - top)
            if (item.locked) {
                drawLock(graphics, left, textY, text)
                left += LOCK_WIDTH
            }
            graphics.drawString(font, label, left, textY, text, false)
        }
    }

    /** A 7x8 padlock with its top-left at [x], [y]. */
    fun drawLock(graphics: GuiGraphics, x: Int, y: Int, color: Int) {
        graphics.fill(x + 1, y, x + 6, y + 1, color)
        graphics.fill(x + 1, y, x + 2, y + 4, color)
        graphics.fill(x + 5, y, x + 6, y + 4, color)
        graphics.fill(x, y + 3, x + 7, y + 8, color)
    }

    /** [this] colour a little darker, for tabs at rest beside the page. */
    private fun Int.darker(theme: UiThemeSnapshot): Int {
        val base = theme.pixelDecorations?.titleBar ?: return this
        // Halfway between the shell and the page's title colour.
        fun channel(shift: Int) = (((this ushr shift) and 0xFF) + ((base ushr shift) and 0xFF)) / 2
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
