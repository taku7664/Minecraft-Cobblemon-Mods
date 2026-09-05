package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveRootSamplerTest {
    @Test
    fun `each accepted draw projects a real public turn and evaluates just one branch`() {
        val contexts = mutableListOf<BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(LocalSelfPlayMeasurement.definitions(1, 20260906).single(),
            maximumTurns = 4, cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY, recordedContexts = contexts)
        val context = contexts.first { PublicRootAllocationExperiment.exclusion(it) == null }
        val replies = PublicFutureActionFactory.actions(context.state, BattleSide.OPPONENT, context.publicActionCatalog)
        val sampler = LiveProjectedRootSampler(context, replies, 7)
        assertNull(sampler.sample(context.candidates.first().actionId, replies.first().actionId) { false })
        assertEquals(0, sampler.projectionCalls)
        repeat(2) {
            val sample = sampler.sample(context.candidates.first().actionId, replies.first().actionId) { true }
            assertNotNull(sample)
            assertTrue(sample!!.value.isFinite() && sample.koProbability in 0.0..1.0)
        }
        assertEquals(2, sampler.projectionCalls)
        assertEquals(2, sampler.leafEvaluations)
        assertTrue(sampler.projectedBranches >= 2)
    }
}
