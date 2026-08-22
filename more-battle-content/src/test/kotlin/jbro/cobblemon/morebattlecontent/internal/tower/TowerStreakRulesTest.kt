package jbro.cobblemon.morebattlecontent.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerStreakRulesTest {
    @Test
    fun `streak stages use the confirmed non-overlapping boundaries`() {
        assertEquals(TowerStreakStage.INTRODUCTORY, TowerStreakStage.forWin(1))
        assertEquals(TowerStreakStage.INTRODUCTORY, TowerStreakStage.forWin(5))
        assertEquals(TowerStreakStage.PRACTICAL, TowerStreakStage.forWin(6))
        assertEquals(TowerStreakStage.PRACTICAL, TowerStreakStage.forWin(10))
        assertEquals(TowerStreakStage.ADVANCED, TowerStreakStage.forWin(11))
        assertEquals(TowerStreakStage.ADVANCED, TowerStreakStage.forWin(20))
        assertEquals(TowerStreakStage.PRO, TowerStreakStage.forWin(21))
        assertEquals(TowerStreakStage.PRO, TowerStreakStage.forWin(Int.MAX_VALUE))
    }

    @Test
    fun `each later stage pays more bp`() {
        assertEquals(listOf(1, 2, 3, 4), TowerStreakStage.entries.map { it.bpPerWin })
    }
}
