package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WildSpawnLevelTest {
    private val rule = WildLevelRule()

    @Test fun `a high region grows with the player cap without changing its actual level`() {
        assertEquals(15..21, rule.range(24, 83))
        assertEquals(74..80, rule.range(100, 83))
        assertEquals(1..7, rule.range(100, 10))
        assertEquals(11..17, rule.range(100, 20))
    }

    @Test fun `spawn levels never use the ten to eighty three regional bounds`() {
        assertEquals(1, WildSpawnLevel.roll(100, rule, 10, 0.0))
        assertEquals(7, WildSpawnLevel.roll(100, rule, 10, 0.999999))
        assertEquals(74, WildSpawnLevel.roll(100, rule, 83, 0.0))
        assertEquals(80, WildSpawnLevel.roll(100, rule, 83, 0.999999))
    }

    @Test fun `cap limiting comes before uniform sampling rather than collapsing every roll to the ceiling`() {
        val counts = (0 until 700).map { WildSpawnLevel.roll(24, rule, 83, it / 700.0) }.groupingBy { it }.eachCount()
        assertEquals((15..21).toSet(), counts.keys)
        assertTrue(counts.values.all { it in 99..101 }, counts.toString())
    }

    @Test fun `every ordinary cap and region preserves the player buffer and a narrow band`() {
        for (cap in 4..100) for (region in 10..83) {
            val levels = listOf(0.0, 0.1, 0.5, 0.9, 0.999999).map { WildSpawnLevel.roll(cap, rule, region, it) }
            assertTrue(levels.all { it in 1..(cap - 3) })
            assertTrue(levels.max() - levels.min() <= 6)
            assertEquals(rule.range(cap, region).first, levels.first())
            assertEquals(rule.range(cap, region).last, levels.last())
        }
    }

    @Test fun `invalid inputs and a configuration that removes the buffer are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 83, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 9, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(0, rule, 20, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(belowCap = 3, spread = 1) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(regionChunks = 0) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(regionMin = 84, regionMax = 83) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(transitionRegions = 0) }
    }
}
