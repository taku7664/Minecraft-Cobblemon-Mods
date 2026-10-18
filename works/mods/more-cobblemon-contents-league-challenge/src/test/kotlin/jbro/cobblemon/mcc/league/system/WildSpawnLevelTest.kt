package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WildSpawnLevelTest {
    private val rule = WildLevelRule()

    @Test fun `a high region grows with the player cap without changing its actual level`() {
        assertEquals(9..19, rule.range(24, 85))
        assertEquals(70..80, rule.range(100, 85))
        assertEquals(1..5, rule.range(100, 10))
        assertEquals(5..15, rule.range(100, 20))
        assertEquals(1..11, rule.range(16, 85))
    }

    @Test fun `spawn levels never use the ten to eighty five regional bounds`() {
        assertEquals(1, WildSpawnLevel.roll(100, rule, 10, 0.0))
        assertEquals(5, WildSpawnLevel.roll(100, rule, 10, 0.999999))
        assertEquals(70, WildSpawnLevel.roll(100, rule, 85, 0.0))
        assertEquals(80, WildSpawnLevel.roll(100, rule, 85, 0.999999))
    }

    @Test fun `cap limiting comes before uniform sampling rather than collapsing every roll to the ceiling`() {
        val counts = (0 until 1100).map { WildSpawnLevel.roll(24, rule, 85, it / 1100.0) }.groupingBy { it }.eachCount()
        assertEquals((9..19).toSet(), counts.keys)
        assertTrue(counts.values.all { it in 99..101 }, counts.toString())
    }

    @Test fun `every ordinary cap and region preserves the player buffer and a narrow band`() {
        for (cap in 4..100) for (region in 10..85) {
            val levels = listOf(0.0, 0.1, 0.5, 0.9, 0.999999).map { WildSpawnLevel.roll(cap, rule, region, it) }
            assertTrue(levels.all { it in 1..maxOf(1, cap - 5) })
            assertTrue(levels.max() - levels.min() <= 10)
            assertEquals(rule.range(cap, region).first, levels.first())
            assertEquals(rule.range(cap, region).last, levels.last())
        }
    }

    @Test fun `invalid inputs and a configuration that removes the buffer are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 85, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 9, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(0, rule, 20, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(belowCap = 3, spread = 1) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(regionChunks = 0) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(regionMin = 86, regionMax = 85) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(transitionRegions = 0) }
    }
}
