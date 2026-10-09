package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildSpawnRegressionTest {
    @Test fun `a single area has only a seven level spawn band`() {
        val rule = WildLevelRule()
        val levels = (0 until 1000).map { WildSpawnLevel.roll(100, rule, 83, it / 1000.0) }
        assertTrue(levels.max() - levels.min() <= 6, "area spawns ${levels.min()}..${levels.max()}")
    }

    @Test fun `the default reduction is six plus or minus three`() {
        val rule = WildLevelRule()
        assertEquals(6, rule.belowCap)
        assertEquals(3, rule.spread)
    }
}
