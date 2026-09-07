package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalDecisionTraceSelectorTest {
    @Test
    fun `observing final ranks preserves actual brain decision and tags`() {
        val context = LocalContestedDecisionCatalog.all(LocalTacticalBrainSimulationTest()).first().context
        val observer = LocalDecisionTraceSelector()
        val traced = LocalTacticalBrain(actionSelector = observer)
        val plain = LocalTacticalBrain()
        val open = BattleBrainOpenContext(context.state.battleId, context.state.format,
            trainerProfile = BattleTrainerProfile.balanced(2))
        val actual = traced.decide(traced.openSession(open), context).toCompletableFuture().get()
        val expected = plain.decide(plain.openSession(open), context).toCompletableFuture().get()
        assertEquals(expected.actionId, actual.actionId)
        assertEquals(expected.tags, actual.tags)
        val trace = requireNotNull(observer.latest)
        assertEquals(actual.actionId, trace.selection.rank.outcome.candidate.actionId)
        assertTrue(trace.ranked.any { it.outcome.candidate.actionId == actual.actionId })
        assertEquals(trace.seed, trace.selection.seed)
    }
}
