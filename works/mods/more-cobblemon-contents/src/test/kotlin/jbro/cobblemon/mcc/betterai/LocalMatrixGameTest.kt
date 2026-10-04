package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.search.LocalMatrixGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalMatrixGameTest {
    @Test
    fun `a pure saddle point is the maximin cell`() {
        val solution = LocalMatrixGame.solve(arrayOf(doubleArrayOf(3.0, 5.0), doubleArrayOf(1.0, 4.0)))
        assertEquals(3.0, solution.value, 1e-12)
        assertEquals(1.0, solution.rowStrategy[0], 1e-12)
        assertEquals(1.0, solution.columnStrategy[0], 1e-12)
    }

    @Test
    fun `rock paper scissors is an even mix worth nothing`() {
        val solution = LocalMatrixGame.solve(arrayOf(
            doubleArrayOf(0.0, -1.0, 1.0),
            doubleArrayOf(1.0, 0.0, -1.0),
            doubleArrayOf(-1.0, 1.0, 0.0),
        ))
        assertEquals(0.0, solution.value, 0.02)
        solution.rowStrategy.forEach { assertEquals(1.0 / 3.0, it, 0.02) }
        solution.columnStrategy.forEach { assertEquals(1.0 / 3.0, it, 0.02) }
    }

    @Test
    fun `a switch the opponent cannot see coming is worth more than its worst reply`() {
        // Rows: stay and attack, switch to a resist. Columns: the opponent's strong move, its coverage move.
        // The worst reply to each row alone makes both rows worth -2, but the opponent cannot pick both.
        val table = arrayOf(doubleArrayOf(-2.0, 1.0), doubleArrayOf(1.0, -2.0))
        val solution = LocalMatrixGame.solve(table)
        assertEquals(-0.5, solution.value, 0.02)
        assertEquals(0.5, solution.rowStrategy[1], 0.02)
    }
}
