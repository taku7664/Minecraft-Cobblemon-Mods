package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Evasion reaches the knockout the ranking is willing to pay for.
 *
 * Accuracy and evasion stages are resolved in exactly one place, and until now only the outcome
 * projection called it - so a Double Team changed the projection made *after* the choice and not the
 * choice itself. The damage term escapes that, because the outcome evaluator cancels the scorer's
 * copy and substitutes the projector's figure. The knockout term has no such cancellation, so it has
 * to ask directly.
 *
 * The facts keep the printed accuracy, which is what the contract says `baseAccuracyProbability` is.
 * Nothing here rewrites a published field; the consumer that needed the resolved number asks the
 * function that computes it.
 */
class LocalEvasionKnockoutTest {
    @Test
    fun `two evasion stages are worth three fifths of the knockout`() {
        val plain = knockoutUtility(targetEvasion = 0)
        assertTrue(plain > 0.0, "The hit kills a plain target, was $plain.")
        val evasive = knockoutUtility(targetEvasion = 2)
        assertEquals(
            plain * 0.6, evasive, 1.0e-9,
            "Two evasion stages are three fifths, and the knockout has to be paid for at that rate.",
        )
    }

    @Test
    fun `an accuracy drop on the attacker reads the same`() {
        val plain = knockoutUtility(targetEvasion = 0)
        val blinded = knockoutUtility(actorAccuracy = -2)
        assertEquals(plain * 0.6, blinded, 1.0e-9, "Sand Attack twice is the same three fifths.")
    }

    @Test
    fun `the facts still publish the printed accuracy`() {
        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context(targetEvasion = 2)).candidates.single().facts,
        )
        assertEquals(
            1.0, requireNotNull(facts.baseAccuracyProbability), 1.0e-9,
            "The contract says this field is the move's own accuracy, and Router is told so. " +
                "Making it carry the stages would be the easy fix and a contract break.",
        )
    }

    private fun knockoutUtility(targetEvasion: Int = 0, actorAccuracy: Int = 0): Double {
        val calculated = PublicBattleTacticalCalculator.calculate(context(targetEvasion, actorAccuracy))
        return LocalTacticalScorer.knockoutUtility(
            candidate = calculated.candidates.single(),
            context = calculated,
        )
    }

    private fun context(targetEvasion: Int = 0, actorAccuracy: Int = 0): BattleDecisionContext {
        val ally = mon(BattleSide.ALLY, if (actorAccuracy == 0) emptyMap() else mapOf("accuracy" to actorAccuracy))
        val opponent = mon(BattleSide.OPPONENT, if (targetEvasion == 0) emptyMap() else mapOf("evasion" to targetEvasion))
        val move = BattleActionCandidate(
            actionId = "probe", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:probe", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = "psychic", damageCategory = BattleMoveDamageCategory.SPECIAL, power = 150.0,
                accuracy = 100.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    private fun mon(side: BattleSide, stages: Map<String, Int>) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
        statusId = null, statStages = stages, knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 150, 100, 100)
        } else {
            publicExactStats(100, 100, 100, 100, 75, 100)
        },
    )
}
