package jbro.cobblemon.mcc.internal.factory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryVictoryRewardTest {
    private fun bp(win: Int) = FactoryProgression.victoryRewardBp(win, FactoryBattleFormat.SINGLE)

    @Test
    fun `a win pays more each round, more for a round's last battle and most for a Factory Head`() {
        assertEquals(List(6) { 4L }, (1..6).map(::bp))
        assertEquals(4L + 10L, bp(7))
        assertEquals(List(6) { 5L }, (8..13).map(::bp))
        assertEquals(5L + 10L, bp(14))
        assertEquals(List(6) { 6L }, (15..20).map(::bp))
        assertEquals(6L + 10L + 15L, bp(21))
        assertEquals(6L, bp(22))
        assertEquals(6L + 10L + 15L, bp(49))
        // The first three rounds, through the first Factory Head: 38 + 45 + 67.
        assertEquals(150L, (1..21).sumOf(::bp))
    }
}
