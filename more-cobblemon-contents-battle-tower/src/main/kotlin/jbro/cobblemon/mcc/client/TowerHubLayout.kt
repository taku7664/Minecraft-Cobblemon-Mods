package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect

/**
 * Battle Tower inside whatever rectangle of the hub content area it is given: a progress strip, the party card
 * beside the setup card, and a footer. Party cells switch between a portrait-beside-text row and a
 * portrait-above-text tile as the card grows.
 */
internal data class TowerHubLayout(
    val strip: UiRect,
    val party: UiRect,
    val setup: UiRect,
    val footer: UiRect,
) {
    /** Where one party member sits inside the party card body, and how its portrait and text share the cell. */
    data class PartyCell(val bounds: UiRect, val portrait: UiRect, val text: UiRect, val stacked: Boolean)

    companion object {
        const val PARTY_SIZE = 6
        private const val CELL_GAP = 2

        /** Narrowest cell of a three-column party grid. */
        private const val THREE_COLUMN_CELL = 84

        fun calculate(bounds: UiRect): TowerHubLayout {
            val gap = MccHubKit.GAP
            val strip = UiRect(bounds.x, bounds.y, bounds.width, MccHubKit.STRIP_HEIGHT)
            val footer = UiRect(bounds.x, bounds.bottom - MccHubKit.FOOTER_HEIGHT, bounds.width, MccHubKit.FOOTER_HEIGHT)
            val body = UiRect(bounds.x, strip.bottom + gap, bounds.width, (footer.y - gap - strip.bottom - gap).coerceAtLeast(1))
            val (party, setup) = MccHubKit.columns(body, 58, 42)
            return TowerHubLayout(strip, party, setup, footer)
        }

        /** The six party cells laid out over a party card [body]. */
        fun partyCells(body: UiRect): List<PartyCell> {
            val columns = if (body.width >= THREE_COLUMN_CELL * 3 + CELL_GAP * 2) 3 else 2
            val rows = (PARTY_SIZE + columns - 1) / columns
            val cellWidth = (body.width - CELL_GAP * (columns - 1)) / columns
            val cellHeight = (body.height - CELL_GAP * (rows - 1)) / rows
            // A roughly square tile reads better with the portrait on top; a wide one with it beside the text.
            val stacked = cellHeight >= 44 && cellWidth * 5 <= cellHeight * 7
            return (0 until PARTY_SIZE).map { index ->
                val cell = UiRect(body.x + (index % columns) * (cellWidth + CELL_GAP), body.y + (index / columns) * (cellHeight + CELL_GAP),
                    cellWidth.coerceAtLeast(1), cellHeight.coerceAtLeast(1))
                if (stacked) {
                    val textHeight = 22
                    val side = (cell.height - textHeight - 4).coerceAtMost(cell.width - 4).coerceAtLeast(8)
                    PartyCell(cell, UiRect(cell.x + (cell.width - side) / 2, cell.y + 2, side, side),
                        UiRect(cell.x + 4, cell.bottom - textHeight, cell.width - 8, textHeight - 2), true)
                } else {
                    val side = (cell.height - 4).coerceAtMost(cell.width / 2).coerceAtLeast(8)
                    PartyCell(cell, UiRect(cell.x + 2, cell.y + (cell.height - side) / 2, side, side),
                        UiRect(cell.x + side + 6, cell.y + (cell.height - 20) / 2, (cell.width - side - 9).coerceAtLeast(1), 20), false)
                }
            }
        }
    }
}
