package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveRootAllocatorTest {
    @Test
    fun `setup time is part of the deadline`() {
        var calls = 0
        val result = LiveRootAllocator.run(listOf("a"), listOf("x"),
            RootAllocationPolicy.UCB, 10, 1, 7, clockNanos = { 2_000_000 }, startedAtNanos = 0) { _, _, _ ->
            calls++
            LiveRootSample(1.0, 0.0)
        }
        assertEquals(0, calls)
        assertNull(result.chosen)
    }

    @Test
    fun `all public reply pairs are checked before adaptive allocation`() {
        val pairs = mutableListOf<String>()
        val result = LiveRootAllocator.run(listOf("b", "a"), listOf("y", "x"),
            RootAllocationPolicy.UCB, 12, Long.MAX_VALUE, 7) { a, b, _ ->
            pairs += "$a/$b"
            LiveRootSample(if (a == "b") 1.0 else 0.0, if (b == "y") 0.01 else 0.0)
        }
        assertEquals(listOf("a/x", "a/y", "b/x", "b/y"), pairs.take(4))
        assertEquals(12, result.completedSamples)
        assertTrue(result.coverageComplete)
        assertEquals(0.01, result.worstObservedKoProbability.getValue("b"))
    }

    @Test
    fun `insufficient coverage cannot recommend an action`() {
        val result = LiveRootAllocator.run(listOf("a", "b"), listOf("x", "y"),
            RootAllocationPolicy.UCB, 3, Long.MAX_VALUE, 7) { _, _, _ -> LiveRootSample(1.0, 0.0) }
        assertFalse(result.coverageComplete)
        assertNull(result.chosen)
    }

    @Test
    fun `interrupted calculation is charged but never accepted`() {
        val result = LiveRootAllocator.run(listOf("a"), listOf("x"),
            RootAllocationPolicy.UNIFORM, 10, Long.MAX_VALUE, 7) { _, _, _ -> null }
        assertEquals(1, result.attemptedSamples)
        assertEquals(0, result.completedSamples)
        assertNull(result.chosen)
    }

    @Test
    fun `deadline rejects late results even when a calculator ignores cancellation`() {
        var clock = 0L
        val result = LiveRootAllocator.run(listOf("a"), listOf("x"),
            RootAllocationPolicy.UCB, 10, 1, 7, clockNanos = { clock }) { _, _, _ ->
            clock = 2_000_000
            LiveRootSample(1.0, 0.0)
        }
        assertEquals(1, result.attemptedSamples)
        assertEquals(0, result.completedSamples)
        assertNull(result.chosen)
    }
}
