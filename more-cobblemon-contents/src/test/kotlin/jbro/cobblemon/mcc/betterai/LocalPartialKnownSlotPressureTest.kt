package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** An unrevealed partner does not erase a different slot's publicly revealed attack. */
class LocalPartialKnownSlotPressureTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `known opponent attack pressure remains available when its partner's moves are unknown`() {
        val partial = context(revealPartnerSplash = false)
        val complete = withPartnerSplash(partial)
        fun pressure(source: BattleDecisionContext) = LocalLookaheadStateEvaluator.attackPressure(source.state,
            BattleSide.OPPONENT, source, LocalProjectedActionCalculationCache(), capDamageToRemainingHp = true)
        val expected = pressure(complete)
        assertTrue(expected > 0.01, "The revealed Tackle must have calculable public pressure")
        assertEquals(expected, pressure(partial), 1e-9)
    }

    @Test
    fun `known Choice Band removal retains its root value with an unrevealed partner`() {
        val partial = context(revealPartnerSplash = false)
        val complete = withPartnerSplash(partial)
        fun removalValue(source: BattleDecisionContext): Double {
            val stripped = source.copy(state = source.state.copyState(pokemon = source.state.pokemon.map {
                if (it.side == BattleSide.OPPONENT && it.activeSlot == 0) it.copyState(knownHeldItemId = "") else it
            }))
            val action = source.candidates.single()
            return LocalTacticalScorer.score(action, source) - LocalTacticalScorer.score(action, stripped)
        }
        val expected = removalValue(complete)
        assertTrue(expected > 1.0, "The revealed physical reply must make its Choice Band valuable")
        assertEquals(expected, removalValue(partial), 1e-9)
    }

    @Test
    fun `partial slot pressure does not invent a complete joint turn for an unknown partner`() {
        val partial = context(revealPartnerSplash = false)
        val known = partial.state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        assertTrue(PublicFutureActionFactory.primitiveActionsForPokemon(partial.state, BattleSide.OPPONENT,
            known.battlePokemonId, partial.publicActionCatalog).any { it.moveId == "cobblemon:tackle" })
        assertTrue(PublicFutureActionFactory.actions(partial.state, BattleSide.OPPONENT, partial.publicActionCatalog).isEmpty())
    }

    private fun withPartnerSplash(source: BattleDecisionContext): BattleDecisionContext {
        val partner = source.state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 1 }
        return source.copy(publicActionCatalog = BattlePublicActionCatalogView(source.publicActionCatalog.entries +
            BattlePokemonActionCatalogView(partner.battlePokemonId, listOf(BattlePublicMoveOptionView("cobblemon:splash",
                BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
                    BattleMoveTargetPattern.SELF), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)))
    }

    private fun context(revealPartnerSplash: Boolean): BattleDecisionContext {
        val first = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "")
        val second = fixture.mon(BattleSide.ALLY, 1).copyState(knownHeldItemId = "")
        val known = fixture.mon(BattleSide.OPPONENT, 0, moves = setOf("cobblemon:tackle")).copyState(knownHeldItemId = "choiceband")
        val partner = fixture.mon(BattleSide.OPPONENT, 1).copyState(knownHeldItemId = "")
        val action = BattleActionCandidate("knockoff:0", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:knockoff", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("dark", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10),
            facts = BattleCandidateFactsView(baseAccuracyProbability = 1.0,
                standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                standardDamageFractionRange = BattleDamageFractionRange(0.1, 0.1),
                standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0),
                standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE))
        val source = fixture.context(fixture.state(first, second, known, partner, format = BattleFormat.DOUBLE), action)
            .copy(publicActionCatalog = BattlePublicActionCatalogView(listOf(BattlePokemonActionCatalogView(known.battlePokemonId,
                listOf(BattlePublicMoveOptionView("cobblemon:tackle", BattleMoveCandidateView("normal",
                    BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                moveSetComplete = true))))
        return if (revealPartnerSplash) withPartnerSplash(source) else source
    }
}
