package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The second swap sees the item distribution left by the first move. */
class LocalJointRootItemSwapEvaluationTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `two empty Trick users cannot both acquire the same public Leftovers`() {
        val single = swap(0)
        assertEquals(12.5, LocalTacticalScorer.scoreBreakdown(single, context(single)).itemUtility, 1e-9)
        verify(12.5, joint(swap(0), swap(1)))
    }

    @Test
    fun `two half accurate item swaps price one acquisition when either succeeds`() {
        // Both users have the same public HP and item activity, so either recipient has one value.
        verify(12.5 * 0.75, joint(swap(0, accuracy = 50.0), swap(1, id = "switcheroo", accuracy = 50.0)))
    }

    @Test
    fun `the faster Klutz recipient keeps Leftovers even when the slower Trick also connects`() {
        val action = joint(swap(0), swap(1))
        val source = context(action, firstAbility = "klutz", firstSpeed = 200, secondSpeed = 50)
        // The foe loses its recovery, but the actual recipient's Klutz suppresses allied recovery.
        verify(6.25, action, source)
    }

    @Test
    fun `a second Trick can return a transferred item to the allied team`() {
        val action = joint(swap(0), swap(1))
        val source = context(action, firstItem = "leftovers", targetItem = "", firstSpeed = 200, secondSpeed = 50)
        // Fast actor -> foe -> slow actor. Equal public HP makes this redistribution neutral.
        verify(0.0, action, source)
    }

    private fun verify(expected: Double, action: BattleActionCandidate, source: BattleDecisionContext = context(action)) {
        assertEquals(expected, LocalTacticalScorer.scoreBreakdown(action, source).itemUtility, 1e-9)
        assertEquals(expected, LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss())
            .single().outcome.itemUtility, 1e-9)
    }

    private fun context(action: BattleActionCandidate, firstItem: String = "", targetItem: String = "leftovers",
        firstAbility: String? = null, firstSpeed: Int = 100, secondSpeed: Int = 100): BattleDecisionContext {
        val first = fixture.mon(BattleSide.ALLY, 0, hp = 0.5, ability = firstAbility, speed = firstSpeed)
            .copyState(knownHeldItemId = firstItem)
        val second = fixture.mon(BattleSide.ALLY, 1, hp = 0.5, speed = secondSpeed).copyState(knownHeldItemId = "")
        val target = fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5).copyState(knownHeldItemId = targetItem)
        val partner = fixture.mon(BattleSide.OPPONENT, 1, hp = 0.5).copyState(knownHeldItemId = "")
        return fixture.context(fixture.state(first, second, target, partner, format = BattleFormat.DOUBLE), action)
    }

    private fun joint(vararg actions: BattleActionCandidate) = BattleActionCandidate("joint:swaps", BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId }, componentActions = actions.toList())

    private fun swap(slot: Int, id: String = "trick", accuracy: Double = 100.0) = BattleActionCandidate("$id:$slot",
        BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("psychic", BattleMoveDamageCategory.STATUS, 0.0, accuracy, 0, 10),
        facts = BattleCandidateFactsView(baseAccuracyProbability = accuracy / 100.0))
}
