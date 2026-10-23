package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.brain.*
import jbro.cobblemon.mcc.betterai.policy.*
import jbro.cobblemon.mcc.betterai.search.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalDecisionObserverTest {
    private val context get() = LocalContestedDecisionCatalog.all(LocalTacticalBrainSimulationTest()).first().context

    @Test
    fun `observation preserves scores probabilities and actual action for standard and boss`() {
        val source = context
        for (profile in listOf(BattleTrainerProfile.balanced(2), BattleTrainerProfile.boss())) {
            var expectedStages: LocalDecisionStages? = null
            var actualStages: LocalDecisionStages? = null
            val observer = LocalDecisionTraceObserver()
            val plain = LocalTacticalBrain(searchless = true, stageTrace = { expectedStages = it })
            val traced = LocalTacticalBrain(searchless = true, stageTrace = { actualStages = it }, decisionObserver = observer)
            val expected = decide(plain, source, profile)
            val actual = decide(traced, source, profile)
            assertEquals(expected.actionId, actual.actionId)
            assertEquals(expected.confidence, actual.confidence)
            assertEquals(stableTags(expected.tags), stableTags(actual.tags))
            assertEquals(expectedStages, actualStages)
            assertEquals(1, observer.calls)
            val observed = requireNotNull(observer.latest)
            assertEquals("searchless", observed.mode)
            assertEquals(actual.actionId, observed.selected.outcome.candidate.actionId)
            assertEquals(requireNotNull(actualStages).refined,
                observed.ranked.associate { it.outcome.candidate.actionId to it.comparisonValue })
            assertEquals(actualStages!!.drawProbabilities, observed.selection.probabilitiesByActionId)
            if (profile.difficulty.tier == BattleTrainerTier.BOSS) {
                assertEquals(1, observed.selection.shortlistSize)
                assertEquals(1.0, observed.selection.probability)
            }
        }
    }

    @Test
    fun `boss result is observed without calling an injected selector`() {
        var calls = 0
        val observer = LocalDecisionTraceObserver()
        val brain = LocalTacticalBrain(searchless = true, decisionObserver = observer,
            actionSelector = LocalActionSelector { _, _, _ -> calls++; error("Boss must keep its own selector") })
        val actual = decide(brain, context, BattleTrainerProfile.boss())
        assertEquals(0, calls)
        assertEquals(1, observer.calls)
        assertEquals(actual.actionId, observer.latest!!.selected.outcome.candidate.actionId)
    }

    @Test
    fun `single candidate publishes once without a selector or mixing context`() {
        val source = context.let { it.copy(candidates = it.candidates.take(1)) }
        val observer = LocalDecisionTraceObserver()
        val brain = LocalTacticalBrain(searchless = true, decisionObserver = observer,
            actionSelector = LocalActionSelector { _, _, _ -> error("Single action needs no selector") })
        val actual = decide(brain, source)
        assertEquals(1, observer.calls)
        assertEquals("single_legal", observer.latest!!.mode)
        assertNull(observer.latest!!.mixing)
        assertEquals(actual.actionId, observer.latest!!.selected.outcome.candidate.actionId)
    }

    @Test
    fun `native failure publishes the actual policy choice once`() {
        val observer = LocalDecisionTraceObserver()
        val brain = LocalTacticalBrain(decisionObserver = observer,
            nativeInitialDecision = { _, _, _, _, _, _ ->
                NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.SEARCH_FAILED)
            })
        val actual = decide(brain, context)
        assertEquals(1, observer.calls)
        assertEquals("native_failed_policy", observer.latest!!.mode)
        assertNull(observer.latest!!.mixing)
        assertEquals(actual.actionId, observer.latest!!.selected.outcome.candidate.actionId)
        assertTrue("native_failed_policy_choice" in actual.tags)
    }

    @Test
    fun `a failing diagnostic observer does not change the chosen action`() {
        val source = context
        val expected = decide(LocalTacticalBrain(searchless = true), source)
        val actual = decide(LocalTacticalBrain(searchless = true,
            decisionObserver = LocalDecisionObserver { throw IllegalStateException("diagnostic failure") }), source)
        assertEquals(expected.actionId, actual.actionId)
        assertEquals(expected.confidence, actual.confidence)
        assertEquals(stableTags(expected.tags), stableTags(actual.tags))
    }

    @Test
    fun `a seed override belongs to selection and observation retains both seeds`() {
        val source = context
        val observer = LocalDecisionTraceObserver()
        val brain = LocalTacticalBrain(searchless = true, decisionObserver = observer,
            actionSelector = LocalSeedOverrideActionSelector(Long.MIN_VALUE))
        val actual = decide(brain, source)
        val observed = requireNotNull(observer.latest)
        val reference = LocalWeightedActionSelector().choose(observed.ranked, Long.MIN_VALUE, requireNotNull(observed.mixing))
        assertEquals(reference, observed.selection)
        assertEquals(Long.MIN_VALUE, observed.selection.seed)
        assertEquals(LocalActionChoiceSeed.derive(source.state.battleId, source.state.turn, observed.ranked,
            source.state.pokemon.filter { it.side == BattleSide.ALLY }.map { it.battlePokemonId }), observed.seed)
        assertEquals(actual.actionId, observed.selected.outcome.candidate.actionId)
        assertEquals(1, observer.calls)
    }

    private fun decide(brain: LocalTacticalBrain, context: BattleDecisionContext,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(2)): BattleDecision {
        val session = brain.openSession(BattleBrainOpenContext(context.state.battleId, context.state.format,
            trainerProfile = profile))
        try {
            return brain.decide(session, context).toCompletableFuture().get()
        } finally {
            brain.closeSession(session, BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, context.state.turn))
        }
    }

    private fun stableTags(tags: Set<String>) = tags.filterNot {
        it.startsWith("ability_worlds_") || it.startsWith("lookahead_elapsed_ms_")
    }.toSet()
}
