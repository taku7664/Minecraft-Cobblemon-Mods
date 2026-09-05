package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicRootAllocationExperimentTest {
    @Test
    fun `unrevealed first turn is not accepted as a meaningful allocation comparison`() {
        val contexts = mutableListOf<jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(LocalSelfPlayMeasurement.definitions(1, 20260906).single(),
            maximumTurns = 1, recordedContexts = contexts)
        assertTrue(contexts.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            PublicRootAllocationExperiment.table(contexts.first())
        }
    }

    @Test
    fun `weighted outcomes preserve rare losses instead of averaging branch counts`() {
        val distribution = ProbeDistribution(listOf(ProbeOutcome(0.99, 1.0), ProbeOutcome(0.01, -100.0)))
        assertEquals(-0.01, distribution.mean, 1e-10)
        assertEquals(-100.0, distribution.sample(0.995))
        assertEquals(1.0, distribution.sample(0.0))
        assertThrows(IllegalArgumentException::class.java) {
            ProbeDistribution(listOf(ProbeOutcome(-1.0, 1.0)))
        }
    }

    @Test
    fun `rare catastrophic outcome can fool both policies and must not count as proof of strength`() {
        val safe = ProbeDistribution(listOf(ProbeOutcome(1.0, 0.1)))
        val risky = ProbeDistribution(listOf(ProbeOutcome(0.99, 1.0), ProbeOutcome(0.01, -100.0)))
        assertTrue(safe.mean > risky.mean)
        for (policy in RootAllocationPolicy.entries) {
            val result = RootAllocationProbe.run(listOf("safe", "risky"), 20, policy) {
                // A valid unlucky stream that never encounters the rare loss.
                if (it == "safe") safe.sample(0.0) else risky.sample(0.0)
            }
            assertEquals("risky", result.chosen)
        }
    }

    @Test
    fun `paired seeds replay identical probe results without leaking exact means to allocation`() {
        val table = mapOf(
            "safe" to ProbeDistribution(listOf(ProbeOutcome(1.0, 0.3))),
            "risky" to ProbeDistribution(listOf(ProbeOutcome(0.5, -1.0), ProbeOutcome(0.5, 1.0))),
        )
        val first = PublicRootAllocationExperiment.compare(table, 80, 73)
        assertEquals(first, PublicRootAllocationExperiment.compare(table, 80, 73))
        assertTrue(first.all { it.result.visits.values.sum() == 80 && it.regret >= 0.0 })
    }
}
