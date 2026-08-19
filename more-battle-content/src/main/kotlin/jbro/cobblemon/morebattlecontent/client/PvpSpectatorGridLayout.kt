package jbro.cobblemon.morebattlecontent.client

internal data class PvpSpectatorSlotLayout(
    val index: Int,
    val bounds: TowerPlayRect,
    val face: TowerPlayRect,
    val nameLeft: Int,
    val nameWidth: Int,
)

internal class PvpSpectatorGridLayout private constructor(
    val rows: Int,
    val columns: Int,
    val block: TowerPlayRect,
    val slots: List<PvpSpectatorSlotLayout>,
) {
    internal companion object {
        fun calculate(bounds: TowerPlayRect, nameWidths: List<Int>): PvpSpectatorGridLayout {
            if (nameWidths.isEmpty()) return PvpSpectatorGridLayout(0, 0, TowerPlayRect(bounds.left, bounds.top, 0, 0), emptyList())
            val rows = (bounds.height / ROW_HEIGHT).coerceIn(1, MAX_ROWS).coerceAtMost(nameWidths.size)
            val columns = (nameWidths.size + rows - 1) / rows
            val desiredCellWidth = (nameWidths.maxOrNull()!! + FACE_SIZE + FACE_NAME_GAP).coerceAtLeast(MIN_CELL_WIDTH)
            val maximumCellWidth = ((bounds.width - COLUMN_GAP * (columns - 1)) / columns).coerceAtLeast(1)
            val cellWidth = desiredCellWidth.coerceAtMost(maximumCellWidth)
            val blockWidth = cellWidth * columns + COLUMN_GAP * (columns - 1)
            val blockHeight = rows * ROW_HEIGHT
            val block = TowerPlayRect(bounds.left + (bounds.width - blockWidth) / 2, bounds.top, blockWidth, blockHeight)
            val slots = nameWidths.mapIndexed { index, _ ->
                val column = index / rows
                val row = index % rows
                val cell = TowerPlayRect(
                    block.left + column * (cellWidth + COLUMN_GAP),
                    block.top + row * ROW_HEIGHT,
                    cellWidth,
                    ROW_HEIGHT,
                )
                val face = TowerPlayRect(cell.left, cell.top + 1, FACE_SIZE, FACE_SIZE)
                PvpSpectatorSlotLayout(index, cell, face, face.right + FACE_NAME_GAP, (cell.right - face.right - FACE_NAME_GAP).coerceAtLeast(1))
            }
            return PvpSpectatorGridLayout(rows, columns, block, slots)
        }
    }
}

private const val MAX_ROWS = 10
private const val ROW_HEIGHT = 12
private const val FACE_SIZE = 10
private const val FACE_NAME_GAP = 3
private const val COLUMN_GAP = 6
private const val MIN_CELL_WIDTH = 44
