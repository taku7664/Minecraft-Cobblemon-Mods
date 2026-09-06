package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTransformMovePoolTest {
    @Test
    fun `departure restores original PP once and does not alter sibling or other Pokemon uses`() {
        val actor = UUID.randomUUID()
        val bench = UUID.randomUUID()
        val foe = UUID.randomUUID()
        fun pokemon(id: UUID, side: BattleSide, slot: Int?) = BattlePokemonStateView(
            id, side, slot, "ditto", null, 50, 1.0, null, emptyMap(), setOf("recover"), null, null, false)
        val initial = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1,
            listOf(pokemon(actor, BattleSide.ALLY, 0), pokemon(bench, BattleSide.ALLY, null), pokemon(foe, BattleSide.OPPONENT, 0)),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        fun entry(pp: Int) = BattlePokemonActionCatalogView(actor, listOf(BattlePublicMoveOptionView("recover",
            BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, pp,
                targetPattern = BattleMoveTargetPattern.SELF), BattlePublicMoveKnowledge.EXACT_OWN)), true)
        val catalog = BattlePublicActionCatalogView(listOf(entry(2)), listOf(entry(7)))
        val key = RecursiveMoveUseKey(actor, "recover")
        val foeKey = RecursiveMoveUseKey(foe, "recover")
        val prior = RecursiveActionHistory(moveUses = mapOf(key to 2, foeKey to 3))
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)
        fun switch(id: UUID) = BattleActionCandidate("switch:$id", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = id)
        fun transition(before: BattleStateView, id: UUID, history: RecursiveActionHistory): Pair<BattleStateView, RecursiveActionHistory> {
            val action = switch(id)
            val after = LocalSwitchStateProjector.project(before, BattleSide.ALLY, action)
            val outcome = PublicTurnProjection(after, listOf(BattleSide.ALLY, BattleSide.OPPONENT),
                stateBeforeResidual = after, directDamage = LocalDirectDamageLedger.EMPTY)
            return after to RecursiveHistoryProjector.project(history, before, outcome, action, wait,
                originalPoolPokemonIds = setOf(actor))
        }
        fun canRecover(state: BattleStateView, history: RecursiveActionHistory) =
            PublicFutureActionFactory.actions(state, BattleSide.ALLY, catalog, history).any { it.moveId == "recover" }
        assertFalse(canRecover(initial, prior))
        val (out, left) = transition(initial, bench, prior)
        assertEquals(setOf(actor), left.restoredOriginalPokemonIds)
        assertFalse(key in left.moveUses)
        assertEquals(3, left.moveUses[foeKey])
        val (returned, restored) = transition(out, actor, left)
        assertTrue(canRecover(returned, restored))
        assertEquals(7, PublicFutureActionFactory.actions(returned, BattleSide.ALLY, catalog, restored)
            .single { it.moveId == "recover" }.moveDetails?.currentPp)
        assertTrue(canRecover(returned, restored.copy(moveUses = restored.moveUses + (key to 5))))
        assertFalse(canRecover(initial, prior))
        val exhausted = restored.copy(moveUses = restored.moveUses + (key to 7))
        val (outAgain, leftAgain) = transition(returned, bench, exhausted)
        val (returnedAgain, finalHistory) = transition(outAgain, actor, leftAgain)
        assertEquals(7, finalHistory.moveUses[key])
        assertFalse(canRecover(returnedAgain, finalHistory))
    }
}
