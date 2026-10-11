package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildSpawnRegressionTest {
    @Test fun `a single area has only an eleven level spawn band`() {
        val rule = WildLevelRule()
        val levels = (0 until 1000).map { WildSpawnLevel.roll(100, rule, 83, it / 1000.0) }
        assertTrue(levels.max() - levels.min() <= 10, "area spawns ${levels.min()}..${levels.max()}")
    }

    @Test fun `the default reduction is ten plus or minus five`() {
        val rule = WildLevelRule()
        assertEquals(10, rule.belowCap)
        assertEquals(5, rule.spread)
    }
}
