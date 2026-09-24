package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalInferredMoveGuessScorer
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalInferredMoveGuessScorerTest {
    @Test
    fun `status guesses never give taunt direct score`() {
        val tuning = LocalDecisionTuning.CURRENT
        val base = context(includeGuaranteedKo = false)
        assertEquals(0.0, LocalInferredMoveGuessScorer.score(taunt, base), 1.0e-9)
        assertEquals(0.0, LocalInferredMoveGuessScorer.score(taunt, context(includeGuaranteedKo = true)), 1.0e-9)
    }

    @Test
    fun `taunt stays scoreless when the target is already taunted`() {
        assertEquals(0.0, LocalInferredMoveGuessScorer.score(
            taunt,
            context(includeGuaranteedKo = false, targetTaunted = true),
        ), 1.0e-9)
    }

    @Test
    fun `a guess never becomes an opponent action`() {
        val context = context(false)
        val inference = context.publicActionCatalog.inferredMovesForPokemon(OPPONENT_ID)!!
        assertEquals(2, inference.slots.count { it.knowledge == BattleOpponentMoveKnowledge.GUESS })
        assertEquals(0, inference.slots.count { it.moveId != null })
        val actions = PublicFutureActionFactory.actions(
            context.state,
            BattleSide.OPPONENT,
            context.publicActionCatalog,
            includeMoveHypotheses = true,
            unknownMovePokemonIds = setOf(OPPONENT_ID),
        )
        assertEquals(0, actions.count { it.kind == BattleActionKind.USE_MOVE })
    }

    private fun context(includeGuaranteedKo: Boolean, targetTaunted: Boolean = false): BattleDecisionContext {
        val opponent = pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, targetTaunted)
        val ally = pokemon(ALLY_ID, BattleSide.ALLY, 0)
        val state = BattleStateView(
            UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(ally, opponent),
            BattleFieldStateView.empty(), BattleSide.entries.associateWith { 1 }, emptyList(), emptyList(),
        )
        val slots = listOf(BattleOpponentMoveGroup.STATUS_OTHER, BattleOpponentMoveGroup.PURE_SETUP).mapIndexed {
            index, group -> BattleOpponentMoveSlotView(index, null, group, BattleOpponentMoveKnowledge.GUESS,
                BattleOpponentMoveSource.GROUP_GUESS)
        }
        val candidates = buildList {
            add(taunt)
            if (includeGuaranteedKo) add(BattleActionCandidate(
                "ko", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = "moonblast",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                moveDetails = BattleMoveCandidateView("fairy", BattleMoveDamageCategory.SPECIAL, 95.0, 100.0, 0, 16),
                facts = BattleCandidateFactsView(
                    baseAccuracyProbability = 1.0,
                    typeChartMultiplier = 1.0,
                    baseSameTypeAttackBonus = 1.5,
                    standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                    standardDamageFractionRange = BattleDamageFractionRange(1.0, 1.0),
                    standardDamageRollKoProbabilityRange = BattleFractionRange(1.0, 1.0),
                    standardKnockoutAssessment = BattleKnockoutAssessment.GUARANTEED,
                    calculationCoverage = BattleCalculationCoverage.EXACT,
                ),
            ))
        }
        return BattleDecisionContext(
            UUID.randomUUID(), state, candidates, Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(emptyList(), opponentMoveInferences = listOf(
                BattleOpponentMoveInferenceView(OPPONENT_ID, slots),
            )),
        )
    }

    private fun pokemon(id: UUID, side: BattleSide, slot: Int, taunted: Boolean = false) = BattlePokemonStateView(
        id, side, slot, "probe", null, 50, 1.0, null, emptyMap(), emptySet(), null, null,
        false, setOf("normal"), actionConstraints = BattlePokemonActionConstraintView(taunted = taunted),
    )

    private val taunt = BattleActionCandidate(
        "taunt", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "taunt",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("dark", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 32),
    )

    private companion object {
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000012")
    }
}
