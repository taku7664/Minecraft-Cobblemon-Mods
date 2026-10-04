package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiCross
import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiGridOrder
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiLength
import jbro.cobblemon.uikit.UiRect

internal data class PvpSpectatorSlotLayout(
    val index: Int,
    val bounds: UiRect,
    val face: UiRect,
    val nameLeft: Int,
    val nameWidth: Int,
)

/** Spectators as face-and-name cells filling columns top to bottom, the block centred in its room. */
internal class PvpSpectatorGridLayout private constructor(
    val rows: Int,
    val columns: Int,
    val block: UiRect,
    val slots: List<PvpSpectatorSlotLayout>,
) {
    internal companion object {
        fun calculate(bounds: UiRect, nameWidths: List<Int>): PvpSpectatorGridLayout {
            if (nameWidths.isEmpty()) return PvpSpectatorGridLayout(0, 0, UiRect(bounds.x, bounds.y, 0, 0), emptyList())
            val rows = (bounds.height / ROW_HEIGHT).coerceIn(1, MAX_ROWS).coerceAtMost(nameWidths.size)
            val columns = (nameWidths.size + rows - 1) / rows
            val desiredCellWidth = (nameWidths.maxOrNull()!! + FACE_SIZE + FACE_NAME_GAP).coerceAtLeast(MIN_CELL_WIDTH)
            val maximumCellWidth = ((bounds.width - COLUMN_GAP * (columns - 1)) / columns).coerceAtLeast(1)
            val cellWidth = desiredCellWidth.coerceAtMost(maximumCellWidth)
            val cells = nameWidths.indices.map { index ->
                UiLayout.layers(UiLayout.leaf("cell.$index"), UiLayout.row {
                    fixed(FACE_SIZE, "face.$index", UiCross(FACE_SIZE, before = 1))
                    space(FACE_NAME_GAP)
                    weight("name.$index", min = 1)
                })
            }
            val grid = UiLayout.grid(List(columns) { UiLength.Fixed(cellWidth) }, List(rows) { UiLength.Fixed(ROW_HEIGHT) }, cells,
                columnGap = COLUMN_GAP, order = UiGridOrder.COLUMN_MAJOR)
            val size = grid.measure()
            val layout = UiLayout.align(UiLayout.layers(UiLayout.leaf("block"), grid), size.width, size.height,
                vertical = UiCrossAlignment.START).solve(bounds)
            val slots = nameWidths.indices.map { index ->
                val name = layout["name.$index"]
                PvpSpectatorSlotLayout(index, layout["cell.$index"], layout["face.$index"], name.x, name.width)
            }
            return PvpSpectatorGridLayout(rows, columns, layout["block"], slots)
        }
    }
}

private const val MAX_ROWS = 10
private const val ROW_HEIGHT = 12
private const val FACE_SIZE = 10
private const val FACE_NAME_GAP = 3
private const val COLUMN_GAP = 6
private const val MIN_CELL_WIDTH = 44
