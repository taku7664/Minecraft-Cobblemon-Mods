package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootAllocationProbeTest {
    @Test
    fun `both policies spend exactly the budget and cover every action first`() {
        for (policy in RootAllocationPolicy.entries) {
            val seen = mutableListOf<String>()
            val result = RootAllocationProbe.run(listOf("a", "b", "c"), 30, policy) {
                seen += it
                if (it == "b") 1.0 else 0.0
            }
            assertEquals(listOf("a", "b", "c"), seen.take(3))
            assertEquals(30, result.visits.values.sum())
            assertEquals("b", result.chosen)
            if (policy == RootAllocationPolicy.UNIFORM) assertEquals(setOf(10), result.visits.values.toSet())
            else assertTrue(result.visits.getValue("b") > result.visits.getValue("a"))
        }
    }

    @Test
    fun `ordering is canonical and repeated results are deterministic`() {
        fun run(ids: List<String>) = RootAllocationProbe.run(ids, 40, RootAllocationPolicy.UCB) { 0.5 }
        assertEquals(run(listOf("b", "a")), run(listOf("a", "b")))
        assertEquals("a", run(listOf("b", "a")).chosen)
    }

    @Test
    fun `invalid budgets ids and nonfinite samples are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootAllocationProbe.run(listOf("a", "b"), 1, RootAllocationPolicy.UCB) { 0.0 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootAllocationProbe.run(listOf("a", "a"), 4, RootAllocationPolicy.UCB) { 0.0 }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootAllocationProbe.run(listOf("a"), 4, RootAllocationPolicy.UCB) { Double.NaN }
        }
    }
}
