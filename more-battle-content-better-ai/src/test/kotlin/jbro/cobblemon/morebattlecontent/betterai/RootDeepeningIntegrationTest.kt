package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootDeepeningIntegrationTest {
    @Test
    fun `live completed scores match recursive referee and total budget includes denied checks`() {
        val contexts = mutableListOf<BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(LocalSelfPlayMeasurement.definitions(1, 20260906).single(),
            maximumTurns = 4, cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts)
        val context = PublicBattleTacticalCalculator.calculate(contexts.first { PublicRootAllocationExperiment.exclusion(it) == null })
        val reference = RootObjectiveReference.evaluate(context, 2)
        assertTrue(reference.matches)
        for (policy in RootDeepeningPolicy.entries) {
            val evaluator = LiveRecursiveRootEvaluator(context)
            val result = RootDeepeningAllocator.run(context.candidates.map { it.actionId }, policy, 200_000, evaluate = evaluator::evaluate)
            assertTrue(result.targetDepthComplete)
            for ((id, score) in result.scores) assertEquals(reference.scores.getValue(id), score, 1e-9)
            assertEquals(reference.isolatedRanking.first(), result.chosen)
            assertEquals(result.nodes, result.attempts.sumOf { it.reading.nodes })
        }
        val evaluator = LiveRecursiveRootEvaluator(context)
        for (depth in 1..2) for (budget in listOf(1, 2, 3, 4, 10, 50, 100)) {
            val reading = evaluator.evaluate(context.candidates.first().actionId, depth, budget)
            assertTrue(reading.nodes <= budget)
        }
    }
}
