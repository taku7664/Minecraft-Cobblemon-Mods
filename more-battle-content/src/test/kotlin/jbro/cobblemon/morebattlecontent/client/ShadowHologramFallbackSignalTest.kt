package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramFallbackSignalTest {
    @Test
    fun `shader-pack fallback preserves the original three-region glitch cadence`() {
        val active = (0L..4_000L).asSequence()
            .map { tick -> tick to ShadowHologramFallbackSignal.sample(tick.toDouble(), 67.25) }
            .first { (_, sample) -> sample.horizontalOffset != 0F }

        assertTrue(kotlin.math.abs(active.second.horizontalOffset) <= 0.042F)
        val neighbouringRegion = ShadowHologramFallbackSignal.sample(active.first.toDouble(), 67.90)
        assertTrue(kotlin.math.abs(neighbouringRegion.horizontalOffset) < 0.000001F)
    }

    @Test
    fun `fallback signal is deterministic but visibly changes over time`() {
        val first = ShadowHologramFallbackSignal.sample(640.0, 68.0)
        assertEquals(first, ShadowHologramFallbackSignal.sample(640.0, 68.0))

        val distinct = (640L..720L)
            .map { ShadowHologramFallbackSignal.sample(it.toDouble(), 68.0) }
            .toSet()
        assertNotEquals(1, distinct.size)
    }
}
