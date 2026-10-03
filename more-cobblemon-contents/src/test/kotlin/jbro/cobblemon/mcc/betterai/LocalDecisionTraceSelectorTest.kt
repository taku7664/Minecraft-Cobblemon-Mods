package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalDecisionTraceSelectorTest {
    @Test
    fun `observing final ranks preserves actual brain decision and tactical tags`() {
        val context = LocalContestedDecisionCatalog.all(LocalTacticalBrainSimulationTest()).first().context
        val observer = LocalDecisionTraceSelector()
        val traced = LocalTacticalBrain(actionSelector = observer)
        val plain = LocalTacticalBrain()
        val open = BattleBrainOpenContext(context.state.battleId, context.state.format,
            trainerProfile = BattleTrainerProfile.balanced(2))
        val actual = traced.decide(traced.openSession(open), context).toCompletableFuture().get()
        val expected = plain.decide(plain.openSession(open), context).toCompletableFuture().get()
        assertTrue("contextual_human_mix" in expected.tags)
        assertFalse("highest_ranked" in expected.tags)
        assertEquals(expected.actionId, actual.actionId)
        // The observation wrapper delegates the draw but has its own selector identity.
        val selectorTags = setOf("mixed_top40", "contextual_human_mix", "evidence_gated_mixup")
        fun stableTags(tags: Set<String>) = tags.filterNot {
            it.startsWith("lookahead_elapsed_ms_") || it in selectorTags
        }.toSet()
        assertEquals(stableTags(expected.tags), stableTags(actual.tags))
        assertEquals(1, expected.tags.count { it.startsWith("lookahead_elapsed_ms_") })
        assertEquals(1, actual.tags.count { it.startsWith("lookahead_elapsed_ms_") })
        val trace = requireNotNull(observer.latest)
        assertEquals(actual.actionId, trace.selection.rank.outcome.candidate.actionId)
        assertTrue(trace.ranked.any { it.outcome.candidate.actionId == actual.actionId })
        assertEquals(trace.seed, trace.selection.seed)
        // Diagnostic overrides leave ranks and mixing intact and retain the original derived seed.
        for (fixedSeed in listOf(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val fixed = LocalDecisionTraceSelector(choiceSeedOverride = fixedSeed)
            val reference = LocalWeightedActionSelector().choose(trace.ranked, fixedSeed, trace.mixing)
            for (derivedSeed in listOf(123L, 456L)) {
                val selection = fixed.choose(trace.ranked, derivedSeed, trace.mixing)
                assertEquals(reference, selection)
                val fixedTrace = requireNotNull(fixed.latest)
                assertEquals(derivedSeed, fixedTrace.seed)
                assertEquals(fixedSeed, fixedTrace.selection.seed)
                assertEquals(trace.ranked, fixedTrace.ranked)
                assertEquals(trace.mixing, fixedTrace.mixing)
            }
        }
    }
}
