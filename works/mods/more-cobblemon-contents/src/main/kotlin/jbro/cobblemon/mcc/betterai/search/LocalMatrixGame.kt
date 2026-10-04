package jbro.cobblemon.mcc.betterai.search

/**
 * One turn as the simultaneous choice it is: rows are the AI's actions, columns the opponent's, each cell the
 * AI's value when both are chosen together. The opponent does not see the AI's choice before making its own,
 * so the worst reply to each row taken on its own is too dark a reading: a switch or a setup move is punished
 * there by a reply the opponent could only pick if it knew. The equilibrium is what each side can hold without
 * knowing the other's pick.
 *
 * Solved by regret matching+ with linearly weighted averages (the CFR+ update for a matrix game), after a check
 * for a pure saddle point, which settles most small turns without iterating.
 */
internal object LocalMatrixGame {
    class Solution(
        /** The AI's value at the equilibrium. */
        val value: Double,
        val rowStrategy: DoubleArray,
        val columnStrategy: DoubleArray,
    )

    fun solve(values: Array<DoubleArray>, iterations: Int = iterationsFor(values)): Solution {
        val rows = values.size
        require(rows > 0)
        val columns = values[0].size
        require(columns > 0 && values.all { it.size == columns })
        saddle(values, rows, columns)?.let { return it }

        val rowRegret = DoubleArray(rows)
        val columnRegret = DoubleArray(columns)
        val rowAverage = DoubleArray(rows)
        val columnAverage = DoubleArray(columns)
        val row = DoubleArray(rows) { 1.0 / rows }
        val column = DoubleArray(columns) { 1.0 / columns }
        val rowPayoff = DoubleArray(rows)
        val columnPayoff = DoubleArray(columns)
        for (iteration in 1..iterations) {
            // Alternating updates: the row player answers the current column mix, then the column player the new row mix.
            for (i in 0 until rows) {
                var sum = 0.0
                val line = values[i]
                for (j in 0 until columns) sum += line[j] * column[j]
                rowPayoff[i] = sum
            }
            var expected = 0.0
            for (i in 0 until rows) expected += row[i] * rowPayoff[i]
            for (i in 0 until rows) rowRegret[i] = (rowRegret[i] + rowPayoff[i] - expected).coerceAtLeast(0.0)
            normalize(rowRegret, row)

            java.util.Arrays.fill(columnPayoff, 0.0)
            for (i in 0 until rows) {
                val weight = row[i]
                if (weight == 0.0) continue
                val line = values[i]
                for (j in 0 until columns) columnPayoff[j] += weight * line[j]
            }
            var columnExpected = 0.0
            for (j in 0 until columns) columnExpected += column[j] * columnPayoff[j]
            // The column player minimises the AI's value.
            for (j in 0 until columns) columnRegret[j] = (columnRegret[j] + columnExpected - columnPayoff[j]).coerceAtLeast(0.0)
            normalize(columnRegret, column)

            for (i in 0 until rows) rowAverage[i] += iteration * row[i]
            for (j in 0 until columns) columnAverage[j] += iteration * column[j]
        }
        scale(rowAverage)
        scale(columnAverage)
        // The value the AI's average strategy guarantees against the opponent's best answer to it and the value the
        // opponent's average holds the AI to; their mean is the equilibrium value within the solver's error.
        var guaranteed = Double.POSITIVE_INFINITY
        for (j in 0 until columns) {
            var sum = 0.0
            for (i in 0 until rows) sum += rowAverage[i] * values[i][j]
            if (sum < guaranteed) guaranteed = sum
        }
        var heldTo = Double.NEGATIVE_INFINITY
        for (i in 0 until rows) {
            var sum = 0.0
            val line = values[i]
            for (j in 0 until columns) sum += line[j] * columnAverage[j]
            if (sum > heldTo) heldTo = sum
        }
        return Solution((guaranteed + heldTo) / 2.0, rowAverage, columnAverage)
    }

    /** A pure equilibrium: the best row's worst cell is also the worst column's best cell. */
    private fun saddle(values: Array<DoubleArray>, rows: Int, columns: Int): Solution? {
        var maximinRow = 0
        var maximin = Double.NEGATIVE_INFINITY
        for (i in 0 until rows) {
            var low = Double.POSITIVE_INFINITY
            for (j in 0 until columns) if (values[i][j] < low) low = values[i][j]
            if (low > maximin) { maximin = low; maximinRow = i }
        }
        var minimaxColumn = 0
        var minimax = Double.POSITIVE_INFINITY
        for (j in 0 until columns) {
            var high = Double.NEGATIVE_INFINITY
            for (i in 0 until rows) if (values[i][j] > high) high = values[i][j]
            if (high < minimax) { minimax = high; minimaxColumn = j }
        }
        if (minimax - maximin > SADDLE_TOLERANCE) return null
        return Solution(maximin, DoubleArray(rows).also { it[maximinRow] = 1.0 }, DoubleArray(columns).also { it[minimaxColumn] = 1.0 })
    }

    private fun normalize(regret: DoubleArray, into: DoubleArray) {
        val total = regret.sum()
        if (total > 0.0) {
            for (k in regret.indices) into[k] = regret[k] / total
        } else {
            java.util.Arrays.fill(into, 1.0 / into.size)
        }
    }

    private fun scale(weights: DoubleArray) {
        val total = weights.sum()
        if (total > 0.0) for (k in weights.indices) weights[k] /= total
    }

    /** Fewer iterations for a larger table: a doubles turn has hundreds of cells, and each iteration reads all of them. */
    private fun iterationsFor(values: Array<DoubleArray>): Int {
        val cells = values.size * values[0].size
        return when {
            cells <= 100 -> 400
            cells <= 900 -> 200
            else -> 100
        }
    }

    private const val SADDLE_TOLERANCE = 1e-9
}
