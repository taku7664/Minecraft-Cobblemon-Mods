package jbro.cobblemon.morebattlecontent.betterai.evaluation

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.*

/** Small, capped root-only credit for countering a broad guessed move group. */
internal object LocalInferredMoveGuessScorer {
    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
    ): Double {
        if (candidate.kind != BattleActionKind.USE_MOVE || canonical(candidate.moveId) != TAUNT) return 0.0
        if (candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS) return 0.0
        val targets = targetPokemon(candidate, context)
        if (targets.isEmpty()) return 0.0
        return targets.sumOf { target ->
            if (target.actionConstraints.taunted || hasGuaranteedKnockout(context, target.activeSlot)) {
                0.0
            } else {
                val guesses = context.publicActionCatalog.inferredMovesForPokemon(target.battlePokemonId)
                    ?.slots.orEmpty().count { slot ->
                        slot.knowledge == BattleOpponentMoveKnowledge.GUESS &&
                            slot.group in STATUS_GROUPS
                    }
                diminishingBonus(guesses, tuning)
            }
        }.coerceAtMost(tuning.maximumGuessedStatusCounterBonus)
    }

    internal fun diminishingBonus(guessCount: Int, tuning: LocalDecisionTuning): Double {
        if (guessCount <= 0) return 0.0
        var next = tuning.guessedStatusCounterBonus
        var total = 0.0
        repeat(guessCount) {
            total += next
            next *= tuning.guessedStatusCounterDiminishingFactor
        }
        return total.coerceAtMost(tuning.maximumGuessedStatusCounterBonus)
    }

    private fun targetPokemon(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): List<BattlePokemonStateView> {
        val explicit = candidate.targets.filter { it.side == BattleSide.OPPONENT }.mapNotNull { target ->
            context.state.pokemon.singleOrNull {
                it.side == BattleSide.OPPONENT && it.activeSlot == target.slot && !it.fainted
            }
        }
        return if (explicit.isNotEmpty()) explicit else context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
        }
    }

    private fun hasGuaranteedKnockout(context: BattleDecisionContext, targetSlot: Int?): Boolean =
        targetSlot != null && context.candidates.asSequence().flatMap { action ->
            if (action.kind == BattleActionKind.COMPOSITE) action.componentActions.asSequence() else sequenceOf(action)
        }.any { action ->
            action.kind == BattleActionKind.USE_MOVE &&
                action.facts?.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED &&
                action.targets.any { it.side == BattleSide.OPPONENT && it.slot == targetSlot }
        }

    private fun canonical(value: String?): String = value.orEmpty().substringAfter(':')
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private val STATUS_GROUPS = setOf(BattleOpponentMoveGroup.PURE_SETUP, BattleOpponentMoveGroup.STATUS_OTHER)
    private const val TAUNT = "taunt"
}
