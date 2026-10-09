package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WildSpawnRegionsTest {
    private val rule = WildLevelRule()
    private fun level(seed: Long, x: Int, z: Int, dimension: String = "minecraft:overworld") =
        WildSpawnRegions.level(seed, dimension, x * rule.regionChunks, z * rule.regionChunks, rule)

    @Test fun `the saved spawn region is ten even for a nonzero negative origin`() {
        for (seed in listOf(Long.MIN_VALUE, -1, 0, 42, Long.MAX_VALUE)) {
            for (x in -12..-9) for (z in 20..23) {
                assertEquals(10, WildSpawnRegions.level(seed, "minecraft:overworld", x, z, rule, -9, 21))
            }
        }
    }

    @Test fun `all sixteen chunks of a region have the same fixed value`() {
        for (x in -28..-25) for (z in 12..15) {
            assertEquals(WildSpawnRegions.level(42, "minecraft:overworld", -28, 12, rule),
                WildSpawnRegions.level(42, "minecraft:overworld", x, z, rule))
        }
    }

    @Test fun `neighbouring regions change by no more than five throughout the field`() {
        for (seed in listOf(-1234L, 0L, 42L, Long.MAX_VALUE)) for (x in -65..65) for (z in -65..65) {
            val here = level(seed, x, z)
            assertTrue(here in 10..83)
            assertTrue(abs(here - level(seed, x + 1, z)) <= 5, "$x,$z along x")
            assertTrue(abs(here - level(seed, x, z + 1)) <= 5, "$x,$z along z")
        }
    }

    @Test fun `every thirty two region tile has a guaranteed eighty three area including the spawn tile`() {
        for (seed in listOf(Long.MIN_VALUE, -99, 0, 1, 42, Long.MAX_VALUE)) {
            for (dimension in listOf("minecraft:overworld", "minecraft:the_nether")) {
                for (tileX in -4..4) for (tileZ in -4..4) {
                    val anchors = (0..1).flatMap { dx -> (0..1).map { dz ->
                        level(seed, (tileX * 2 + dx) * 16, (tileZ * 2 + dz) * 16, dimension)
                    } }
                    assertEquals(83, anchors.max(), "seed $seed dim $dimension tile $tileX,$tileZ")
                }
            }
        }
    }

    @Test fun `the field repeats exactly without player state while different seeds and dimensions differ`() {
        fun samples(seed: Long, dim: String) = (-20..20).map { level(seed, it, 7, dim) }
        assertEquals(samples(42, "minecraft:overworld"), samples(42, "minecraft:overworld"))
        assertNotEquals(samples(42, "minecraft:overworld"), samples(99, "minecraft:overworld"))
        assertNotEquals(samples(42, "minecraft:overworld"), samples(42, "minecraft:the_nether"))
    }
}
