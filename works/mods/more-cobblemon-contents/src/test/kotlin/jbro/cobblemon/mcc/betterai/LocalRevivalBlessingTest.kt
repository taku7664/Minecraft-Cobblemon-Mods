package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcomeEvaluator
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.mon
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.state
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalRevivalBlessingTest {
    @Test fun `reviving a bench member restores half HP and the count without replacing the user`() {
        val user = mon(BattleSide.ALLY, 0)
        val fallen = mon(BattleSide.ALLY, null, hp = 0.0)
        val state = state(user, fallen, mon(BattleSide.OPPONENT, 0))
        val action = revive(fallen)
        val result = LocalSwitchStateProjector.project(state, BattleSide.ALLY, action)
        val restored = result.pokemon.single { it.battlePokemonId == fallen.battlePokemonId }
        assertFalse(restored.fainted)
        assertEquals(0.5, restored.hpFraction)
        assertNull(restored.activeSlot)
        assertEquals(0, result.pokemon.single { it.battlePokemonId == user.battlePokemonId }.activeSlot)
        assertEquals(2, result.remainingPokemonBySide.getValue(BattleSide.ALLY))
        val outcome = LocalBattleActionOutcomeEvaluator.evaluate(action, context(state, action), null, BattleTrainerProfile.champion())
        assertEquals(0.5, outcome.switchPostEntryHp)
        assertTrue(outcome.tacticalUtility > 200.0, outcome.toString())
        assertFalse(outcome.entryFaints)
        val projected = jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector.project(
            state, action, BattleActionCandidate("wait", BattleActionKind.WAIT), context(state, action),
        )
        assertTrue(projected.isNotEmpty())
        projected.forEach { branch ->
            assertEquals(0.5, branch.stateBeforeResidual.pokemon.single {
                it.battlePokemonId == fallen.battlePokemonId
            }.hpFraction)
        }
    }
    @Test fun `reviving a resistant member is worth more against a revealed matchup`() {
        val water = mon(BattleSide.ALLY, null, "water", hp = 0.0)
        val grass = mon(BattleSide.ALLY, null, "grass", hp = 0.0)
        val state = state(mon(BattleSide.ALLY, 0), water, grass, mon(BattleSide.OPPONENT, 0, "fire"))
        val a = revive(water); val b = revive(grass)
        val ctx = context(state, a, b)
        assertTrue(LocalTacticalScorer.score(a, ctx) > LocalTacticalScorer.score(b, ctx))
    }
    @Test fun `casting Revival Blessing chooses its own best target instead of a random ally`() {
        val water = mon(BattleSide.ALLY, null, "water", hp = 0.0)
        val grass = mon(BattleSide.ALLY, null, "grass", hp = 0.0)
        val state = state(mon(BattleSide.ALLY, 0), water, grass, mon(BattleSide.OPPONENT, 0, "fire"))
        val action = BattleActionCandidate("revivalblessing", BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 0, moveId = "cobblemon:revivalblessing", moveDetails = BattleMoveCandidateView(
                "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 1, BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(
                    BattleMoveEffectView(BattleMoveEffectKind.SLOT_CONDITION, BattleMoveEffectTarget.USER,
                        1.0, valueId = "revivalblessing")), scriptedBehavior = false)))
        val branches = jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector.project(
            state, action, BattleActionCandidate("wait", BattleActionKind.WAIT), context(state, action))
        assertTrue(branches.isNotEmpty())
        branches.forEach { branch ->
            assertFalse(branch.stateBeforeResidual.pokemon.single { it.battlePokemonId == water.battlePokemonId }.fainted)
            assertTrue(branch.stateBeforeResidual.pokemon.single { it.battlePokemonId == grass.battlePokemonId }.fainted)
        }
    }
    private fun revive(target: BattlePokemonStateView) = BattleActionCandidate(
        "revive:${target.battlePokemonId}", BattleActionKind.SWITCH, actorSlot = 0,
        switchPokemonId = target.battlePokemonId, tags = setOf("revival_blessing"),
    )
}
