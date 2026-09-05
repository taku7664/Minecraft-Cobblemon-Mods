package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootObjectiveReferenceTest {
    @Test
    fun `isolated candidates retain whole search scores diagnostics and ranking at both depths`() {
        val context = publicContext()
        for (depth in 1..2) {
            val result = RootObjectiveReference.evaluate(context, depth)
            assertTrue(result.matches)
            assertEquals(context.candidates.size, result.individual.size)
            assertEquals(depth, result.whole.depthCompleted)
            assertEquals(result.whole.ranked.map { it.outcome.candidate.actionId }, result.isolatedRanking)
            assertTrue(result.individual.all { it.evaluation.depthCompleted == depth && !it.evaluation.truncated })
            assertTrue(result.whole.publicResponseIncomplete, "Fixture must exercise unknown public response handling")
        }
    }

    @Test
    fun `unfinished node capped reference is not labeled an exact objective`() {
        assertThrows(IllegalStateException::class.java) {
            RootObjectiveReference.evaluate(publicContext(), depth = 2, nodeLimit = 1)
        }
    }

    @Test
    fun `score loss uses recursive score units and preserves missing recommendations`() {
        val scores = mapOf("a" to 90.0, "b" to 20.0)
        assertEquals(70.0, RootObjectiveReference.loss(scores, "b"))
        assertEquals(0.0, RootObjectiveReference.loss(scores, "a"))
        assertNull(RootObjectiveReference.loss(scores, null))
        assertThrows(IllegalArgumentException::class.java) { RootObjectiveReference.loss(scores, "unknown") }
    }

    private fun publicContext(): BattleDecisionContext {
        val contexts = mutableListOf<BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(LocalSelfPlayMeasurement.definitions(1, 20260906).single(),
            maximumTurns = 4, cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts)
        return PublicBattleTacticalCalculator.calculate(contexts.first { PublicRootAllocationExperiment.exclusion(it) == null })
    }
}
