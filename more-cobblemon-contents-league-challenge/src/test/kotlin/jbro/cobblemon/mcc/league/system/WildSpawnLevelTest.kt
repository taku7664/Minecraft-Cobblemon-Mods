package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WildSpawnLevelTest {
    private val rule = WildLevelRule()
    private val rolls = (0 until 1000).map { it / 1000.0 }

    @Test fun `every spawn lands within seven levels of ten below the cap`() {
        for (lean in listOf(-1.0, -0.4, 0.0, 0.6, 1.0)) {
            val levels = rolls.map { WildSpawnLevel.roll(50, rule, lean, it) }
            assertTrue(levels.all { it in 33..47 }, "lean $lean")
            // The whole range stays reachable wherever the area leans.
            assertEquals(33, levels.min(), "lean $lean")
            assertEquals(47, levels.max(), "lean $lean")
        }
    }

    @Test fun `an even area spreads spawns evenly around the centre`() {
        val levels = rolls.map { WildSpawnLevel.roll(50, rule, 0.0, it) }
        assertEquals(40.0, levels.average(), 0.1)
    }

    @Test fun `a strong area favours the high end and a weak one the low end`() {
        val weak = rolls.map { WildSpawnLevel.roll(50, rule, -1.0, it) }.average()
        val strong = rolls.map { WildSpawnLevel.roll(50, rule, 1.0, it) }.average()
        assertTrue(weak < 38.0, "weak $weak")
        assertTrue(strong > 42.0, "strong $strong")
    }

    @Test fun `low caps never create a zero level pokemon and spawns never exceed the cap`() {
        assertEquals(1, WildSpawnLevel.roll(5, rule, -1.0, 0.0))
        val tight = WildLevelRule(belowCap = 2, spread = 7)
        assertTrue(rolls.all { WildSpawnLevel.roll(30, tight, 1.0, it) <= 30 })
    }

    @Test fun `invalid inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 1.5, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.roll(50, rule, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(spread = -1) }
        assertThrows(IllegalArgumentException::class.java) { WildLevelRule(regionChunks = 0) }
    }

    @Test fun `a chunk's lean is fixed by the world and stays within range`() {
        val lean = WildSpawnRegions.lean(1234L, "minecraft:overworld", 10, -3, 4)
        assertEquals(lean, WildSpawnRegions.lean(1234L, "minecraft:overworld", 10, -3, 4))
        assertNotEquals(lean, WildSpawnRegions.lean(99L, "minecraft:overworld", 10, -3, 4))
        assertNotEquals(lean, WildSpawnRegions.lean(1234L, "minecraft:the_nether", 10, -3, 4))
        for (x in -40..40) for (z in -40..40) {
            assertTrue(WildSpawnRegions.lean(1234L, "minecraft:overworld", x, z, 4) in -1.0..1.0)
        }
    }

    @Test fun `neighbouring chunks lean alike while distant areas differ`() {
        val steps = mutableListOf<Double>()
        val values = mutableListOf<Double>()
        for (x in -64..64) for (z in -64..64) {
            val here = WildSpawnRegions.lean(42L, "minecraft:overworld", x, z, 4)
            values += here
            steps += abs(here - WildSpawnRegions.lean(42L, "minecraft:overworld", x + 1, z, 4))
        }
        // One chunk over moves the lean by at most 2 / regionChunks, but across the map it reaches weak and strong areas.
        assertTrue(steps.max() <= 0.5 + 1e-9, "largest step ${steps.max()}")
        assertTrue(values.min() < -0.5 && values.max() > 0.5, "range ${values.min()}..${values.max()}")
    }
}
