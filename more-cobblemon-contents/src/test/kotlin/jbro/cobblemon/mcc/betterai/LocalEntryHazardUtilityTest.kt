package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalIdleUtilityMoveRules
import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.mon
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.state
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalEntryHazardUtilityTest {
    @Test fun `public selection size supplies the maximum bench denominator for an unseen bench`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0))
            .derive(remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2))
        val move = hazard("toxicspikes")
        val ctx = context(s, move).copy(opponentTeamPreview = BattleOpponentTeamPreviewView(3,
            (0..2).map { BattleOpponentTeamPreviewPokemonView(it, "showdown:probe", null, 50) }))
        assertEquals(20.0 / 2.0, LocalNonDamagingMoveEvaluator.pressure(move, ctx, 1.0), 1e-9)
        assertEquals(0.1 / 2.0, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes"), ctx), 1e-9)
    }
    @Test fun `a three Pokemon singles battle scales hazard value by remaining switches over two`() {
        val a = mon(BattleSide.ALLY, 0)
        for (bench in 0..2) {
            val opponents = listOf(mon(BattleSide.OPPONENT, 0)) + (1..2).map { index ->
                mon(BattleSide.OPPONENT, null, hp = if (index <= bench) 1.0 else 0.0)
            }
            val s = state(a, *opponents.toTypedArray()); val move = hazard("toxicspikes")
            assertEquals(20.0 * bench / 2.0, LocalNonDamagingMoveEvaluator.pressure(move, context(s, move), 1.0), 1e-9)
            assertEquals(0.1 * bench / 2.0, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes")), 1e-9)
        }
    }
    @Test fun `a three Pokemon doubles battle starts with full value for its one bench member`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.ALLY, 1),
            mon(BattleSide.OPPONENT, 0), mon(BattleSide.OPPONENT, 1), mon(BattleSide.OPPONENT, null),
            format = BattleFormat.DOUBLE)
        val move = hazard("toxicspikes")
        assertEquals(20.0, LocalNonDamagingMoveEvaluator.pressure(move, context(s, move), 1.0), 1e-9)
        assertEquals(0.1, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes")), 1e-9)
    }
    @Test fun `a fainted doubles slot does not change the original maximum bench count`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.ALLY, 1),
            mon(BattleSide.OPPONENT, 0), mon(BattleSide.OPPONENT, 1, hp = 0.0), mon(BattleSide.OPPONENT, null),
            format = BattleFormat.DOUBLE)
        val move = hazard("toxicspikes")
        assertEquals(20.0, LocalNonDamagingMoveEvaluator.pressure(move, context(s, move), 1.0), 1e-9)
        assertEquals(0.1, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes")), 1e-9)
    }
    @Test fun `hazards earn no root or leaf value against the last opponent`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0))
        for (id in listOf("toxicspikes", "spikes", "stealthrock", "stickyweb")) {
            val move = hazard(id)
            assertTrue(LocalIdleUtilityMoveRules.isIdle(move, context(s, move)), id)
            assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(move, context(s, move), 1.0), id)
            assertEquals(0.0, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, id)) -
                LocalImmediateTurnScorer.positionEffectValue(s), 1e-9, id)
        }
    }
    @Test fun `two active opponents with no bench also give no hazard value`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.ALLY, 1),
            mon(BattleSide.OPPONENT, 0), mon(BattleSide.OPPONENT, 1), format = BattleFormat.DOUBLE)
        val move = hazard("toxicspikes")
        assertTrue(LocalIdleUtilityMoveRules.isIdle(move, context(s, move)))
        assertEquals(0.0, LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes")), 1e-9)
    }
    @Test fun `an unseen living bench retains hazard utility even if the user has only one Pokemon`() {
        val initial = state(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0))
        val s = initial.derive(remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2))
        val move = hazard("toxicspikes")
        assertFalse(LocalIdleUtilityMoveRules.isIdle(move, context(s, move)))
        assertTrue(LocalNonDamagingMoveEvaluator.pressure(move, context(s, move), 1.0) > 0.0)
        assertTrue(LocalImmediateTurnScorer.positionEffectValue(withHazard(s, "toxicspikes")) > 0.0)
    }
    private fun hazard(id: String) = BattleActionCandidate(id, BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", moveDetails = BattleMoveCandidateView("poison", BattleMoveDamageCategory.STATUS,
            0.0, 100.0, 0, 20, BattleMoveTargetPattern.SIDE,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.TARGET_SIDE,
                    valueId = id)), scriptedBehavior = false)))
    private fun withHazard(s: BattleStateView, id: String) = s.derive(field = BattleFieldStateView(
        null, null, emptyList(), emptyList(), mapOf(BattleSide.ALLY to emptyList(), BattleSide.OPPONENT to listOf(BattleTimedEffectView(id, null))),
    ))
}
