package jbro.cobblemon.mcc.pwt.client

import jbro.cobblemon.uikit.UiRect

/**
 * Where the mirrored bracket goes inside a card body: four quarterfinal faces down each side, the semifinal and
 * final boxes stepping in toward the middle, and the champion's box under the trophy at the centre. Boxes of a
 * round not yet decided have no [Box.slot].
 */
internal data class PwtBracketLayout(val size: Int, val boxes: List<Box>, val lines: List<Line>, val trophy: UiRect) {
    /** [round] 0-2 quarterfinal to final, 3 the champion; [slot] the bracket slot shown, once decided. */
    data class Box(val rect: UiRect, val round: Int, val slot: Int?)

    /** A connector as horizontal and vertical runs; [lit] once the winner went along it. */
    data class Line(val segments: List<UiRect>, val lit: Boolean)

    companion object {
        const val MIN_SIZE = 10
        const val MAX_SIZE = 24

        /** Null when [body] is too small for even the smallest boxes. */
        fun calculate(body: UiRect, winners: List<List<Int>>): PwtBracketLayout? {
            val size = (MAX_SIZE downTo MIN_SIZE).firstOrNull { width(it) <= body.width && height(it) <= body.height } ?: return null
            val gap = rowGap(size)
            val step = size + connector(size)
            val left = body.x + (body.width - width(size)) / 2
            val right = left + width(size)
            val top = body.y + (body.height - height(size)) / 2
            val quarterY = List(4) { top + it * (size + gap) }
            val semiY = List(2) { (quarterY[it * 2] + quarterY[it * 2 + 1]) / 2 }
            val finalY = (semiY[0] + semiY[1]) / 2
            fun x(rightSide: Boolean, column: Int) = if (rightSide) right - size - column * step else left + column * step
            fun box(x: Int, y: Int) = UiRect(x, y, size, size)

            // Slots shown per round, in bracket order: everyone, then each round's winners.
            val shown = listOf((0 until 8).map { it }) + (1..3).map { round -> winners.getOrNull(round - 1) }
            val boxes = ArrayList<Box>()
            for (index in 0 until 8) {
                val side = index >= 4
                boxes += Box(box(x(side, 0), quarterY[index % 4]), 0, index)
            }
            for (index in 0 until 4) {
                val side = index >= 2
                boxes += Box(box(x(side, 1), semiY[index % 2]), 1, shown[1]?.get(index))
            }
            for (index in 0 until 2) boxes += Box(box(x(index == 1, 2), finalY), 2, shown[2]?.get(index))
            val center = (left + right) / 2
            val champion = Box(box(center - size / 2, finalY), 3, shown[3]?.get(0))
            boxes += champion

            val lines = ArrayList<Line>()
            fun connect(child: Box, parent: Box) {
                val rightSide = child.rect.x > center
                val fromX = if (rightSide) child.rect.x else child.rect.right
                val toX = if (rightSide) parent.rect.right else parent.rect.x
                val fromY = child.rect.y + size / 2
                val toY = parent.rect.y + size / 2
                val midX = (fromX + toX) / 2
                val segments = listOf(
                    UiRect(minOf(fromX, midX), fromY, kotlin.math.abs(midX - fromX) + 1, 1),
                    UiRect(midX, minOf(fromY, toY), 1, kotlin.math.abs(toY - fromY) + 1),
                    UiRect(minOf(midX, toX), toY, kotlin.math.abs(toX - midX) + 1, 1),
                )
                lines += Line(segments.filter { it.width > 0 && it.height > 0 }, child.slot != null && child.slot == parent.slot)
            }
            val byRound = boxes.groupBy { it.round }
            byRound.getValue(0).forEachIndexed { index, child -> connect(child, byRound.getValue(1)[index / 2]) }
            byRound.getValue(1).forEachIndexed { index, child -> connect(child, byRound.getValue(2)[index / 2]) }
            byRound.getValue(2).forEach { child -> connect(child, champion) }
            val trophySize = minOf(size, 16)
            val trophy = UiRect(center - trophySize / 2, champion.rect.y - trophySize - 3, trophySize, trophySize)
            return PwtBracketLayout(size, boxes, lines, trophy)
        }

        private fun connector(size: Int) = maxOf(8, size * 3 / 4)
        private fun rowGap(size: Int) = maxOf(3, size / 3)
        /** Three columns a side and the champion's box in the middle, a connector's width between each. */
        fun width(size: Int) = 2 * (3 * size + 3 * connector(size)) + size
        fun height(size: Int) = 4 * size + 3 * rowGap(size)
    }
}
