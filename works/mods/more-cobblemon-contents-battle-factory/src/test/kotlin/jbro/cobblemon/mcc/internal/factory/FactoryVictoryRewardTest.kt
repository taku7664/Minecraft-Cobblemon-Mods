package jbro.cobblemon.mcc.internal.factory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryVictoryRewardTest {
    private fun bp(win: Int) = FactoryProgression.victoryRewardBp(win)

    @Test
    fun `a win pays one more each round, more for a round's last battle and most for a Factory Head`() {
        assertEquals(List(6) { 2L }, (1..6).map(::bp))
        assertEquals(2L + 5L, bp(7))
        assertEquals(List(6) { 3L }, (8..13).map(::bp))
        assertEquals(3L + 5L, bp(14))
        assertEquals(List(6) { 4L }, (15..20).map(::bp))
        assertEquals(4L + 5L + 10L, bp(21))
        assertEquals(5L, bp(22))
        assertEquals(8L + 5L + 10L, bp(49))
        assertEquals(11L, bp(64))
        // The first three rounds, through the first Factory Head: 19 + 26 + 43.
        assertEquals(88L, (1..21).sumOf(::bp))
    }
}
