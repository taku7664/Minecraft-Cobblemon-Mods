package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiLength
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRemainder
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A grid of Pokemon cards (a party, rentals, a team) that fills whatever card body it is given. The grid picks
 * the column count that gives the largest portraits, and each card puts its portrait beside the text when it is
 * wide or above it when it is roughly square.
 */
object MccHubPortraitCards {
    /** One card of the grid and how its portrait and two text lines share it. */
    data class Cell(val bounds: UiRect, val portrait: UiRect, val text: UiRect, val stacked: Boolean)

    private const val GAP = 2
    private const val MIN_WIDTH = 64
    private const val MIN_HEIGHT = 24
    private const val STACKED_TEXT = 22

    /** Lays [count] cards over [body]; an empty list when nothing can fit. */
    fun grid(body: UiRect, count: Int): List<Cell> {
        if (count <= 0) return emptyList()
        val best = (1..count).mapNotNull { columns -> candidate(body, count, columns) }
            .maxWithOrNull(compareBy<Candidate> { it.side }.thenBy { -it.rows }) ?: return emptyList()
        return cells(body, count, best.columns, best.rows).map { cell ->
            val side = best.side
            val layout = if (best.stacked) {
                UiLayout.layers(
                    UiLayout.inset(UiLayout.align(UiLayout.leaf("portrait"), side, side, vertical = UiCrossAlignment.START), top = 2),
                    UiLayout.inset(UiLayout.column {
                        spring()
                        fixed(STACKED_TEXT - 2, "text")
                        space(2)
                    }, left = 4, right = 4),
                )
            } else {
                UiLayout.layers(
                    UiLayout.inset(UiLayout.align(UiLayout.leaf("portrait"), side, side, horizontal = UiCrossAlignment.START), left = 2),
                    UiLayout.inset(UiLayout.align(UiLayout.leaf("text"), height = 20), left = side + 6, right = 3, min = 1),
                )
            }.solve(cell)
            Cell(cell, layout["portrait"], layout["text"], best.stacked)
        }
    }

    private class Candidate(val columns: Int, val rows: Int, val width: Int, val height: Int, val stacked: Boolean, val side: Int)

    /** [count] equal cells in [columns] by [rows], [GAP] apart, filled row by row. */
    private fun cells(body: UiRect, count: Int, columns: Int, rows: Int): List<UiRect> {
        val keys = UiLayout.keys("cell", count)
        return UiLayout.grid(List(columns) { UiLength.Weight() }, List(rows) { UiLength.Weight() }, keys.map(UiLayout::leaf),
            GAP, GAP, remainder = UiRemainder.NONE).solve(body).list("cell")
    }

    private fun candidate(body: UiRect, count: Int, columns: Int): Candidate? {
        val rows = (count + columns - 1) / columns
        val cell = cells(body, 1, columns, rows).first()
        val width = cell.width
        val height = cell.height
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return null
        // A roughly square card reads better with the portrait on top; a wide one with it beside the text.
        val stacked = height >= 44 && width * 5 <= height * 7
        val side = if (stacked) minOf(height - STACKED_TEXT - 4, width - 4) else minOf(height - 4, width / 2)
        if (side < 8) return null
        return Candidate(columns, rows, width, height, stacked, side)
    }

    /**
     * One card: a UI kit portrait (animated while hovered) with a [primary] and a [secondary] line. [marked] cards
     * are picked: their surface shows selected and [primary] turns to the accent colour.
     */
    class Button(
        private val cell: Cell,
        private val portrait: CobblemonUiRenderContent,
        private val primary: Component,
        private val secondary: Component,
        private val marked: Boolean,
        narration: Component,
        private val press: () -> Unit,
    ) : AbstractButton(cell.bounds.x, cell.bounds.y, cell.bounds.width, cell.bounds.height, narration) {
        override fun onPress() = press()

        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val state = when {
                !active && !marked -> UiWidgetState.DISABLED
                isHoveredOrFocused -> UiWidgetState.HOVER
                marked -> UiWidgetState.SELECTED
                else -> UiWidgetState.NORMAL
            }
            val style = theme.style(UiButtonVariant.SECONDARY, state)
            UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
            val frame = cell.portrait
            graphics.fill(frame.x, frame.y, frame.right, frame.bottom, theme.colors.shell)
            CobblemonUiRenderSlot.drawContent(graphics, frame, animated(portrait, isHoveredOrFocused), partialTick)
            val font = Minecraft.getInstance().font
            val text = cell.text
            val first = MccHubKit.fitted(primary, text.width)
            val second = MccHubKit.fitted(secondary, text.width)
            val firstColor = if (marked) theme.colors.accentCaution else style.text
            val shadow = style.textShadowColor
            if (cell.stacked) {
                UiTextRenderer.draw(graphics, font, first, text.x + (text.width - font.width(first)) / 2, text.y, firstColor, shadow)
                UiTextRenderer.draw(graphics, font, second, text.x + (text.width - font.width(second)) / 2, text.y + 10, style.text, shadow)
            } else {
                UiTextRenderer.draw(graphics, font, first, text.x, text.y, firstColor, shadow)
                UiTextRenderer.draw(graphics, font, second, text.x, text.y + 10, style.text, shadow)
            }
            // A marked card keeps its cursor while hovered; themes that fill the selection draw nothing more here.
            val indicator = if (marked) theme.style(UiButtonVariant.SECONDARY, UiWidgetState.SELECTED).selectionIndicator else style.selectionIndicator
            UiSurfaceRenderer.drawSelection(graphics, x, y, width, height, style.surface.shape, indicator)
        }

        private fun animated(content: CobblemonUiRenderContent, animate: Boolean): CobblemonUiRenderContent = when (content) {
            is CobblemonUiRenderContent.Pokemon -> content.copy(animate = animate)
            is CobblemonUiRenderContent.PartyPokemon -> content.copy(animate = animate)
            else -> content
        }
    }
}
