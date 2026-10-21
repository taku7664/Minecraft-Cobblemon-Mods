package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * Whether a heal of our own gets anywhere against the hits it faces, in the root's units.
 *
 * The root credits a heal for the HP it restores as if the other side did nothing. A heal that the opponent's hit
 * undoes, or that still leaves the user to its next hit, was credited as HP kept: played out, a loop that goes nowhere.
 * Only the heal's own score is read here; the hits are the matchup table's.
 *
 * It no longer touches an attack's score (user, 2026-10-21: the heal rule does not reach into another kind's value).
 * Its attack side took an attack's whole damage credit back when the foe had shown a heal at least as large: every
 * attack under that size fell to about nothing, so a +2 SpD Hippowdon's Earthquake (43-51%) and Stone Edge (29-34%) on a
 * Recovering Porygon2 came to -1.1 and -0.6 and Stone Edge was drawn 52%, and sand's 6% at the turn's end that put the Earthquake past the heal was never read.
 */
internal object LocalHealRace {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val weight = tuning.healRaceWeight
        if (weight <= 0.0) return emptyMap()
        val state = context.state
        val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }
        if (opponents.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE) continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && standing(it)
                } ?: continue
                val details = part.moveDetails ?: continue
                if (details.damageCategory == BattleMoveDamageCategory.STATUS && selfHeal(details) > 0.0) {
                    change += heal(user, selfHeal(details), opponents, context, scores, tuning)
                }
            }
            if (change != 0.0) out[candidate.actionId] = change * weight
        }
        return out
    }

    /** A heal of our own against the hits it faces; negative when it goes nowhere. */
    private fun heal(
        user: BattlePokemonStateView,
        heal: Double,
        opponents: List<BattlePokemonStateView>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Double {
        val restored = minOf(heal, 1.0 - user.hpFraction)
        if (restored <= 0.0) return 0.0
        val healed = minOf(1.0, user.hpFraction + heal)
        // The worst of what the opponents in front do to it: undo the heal, or knock it out from the healed HP.
        val futile = opponents.maxOfOrNull { opponent ->
            val best = scores.moves(opponent.battlePokemonId, user.battlePokemonId).maxByOrNull {
                it.accuracy * (it.minimumDamageFraction + it.maximumDamageFraction)
            } ?: return@maxOfOrNull 0.0
            val expected = best.accuracy * (best.minimumDamageFraction + best.maximumDamageFraction) / 2.0
            val knockout = rollChance(best.minimumDamageFraction, best.maximumDamageFraction, healed) * best.accuracy
            maxOf(knockout, if (expected >= restored) 1.0 else 0.0)
        } ?: 0.0
        return -futile * tuning.board(restored)
    }

    /** The share of rolls between [minimum] and [maximum] (spread evenly) that reach [hp]. */
    private fun rollChance(minimum: Double, maximum: Double, hp: Double): Double = when {
        maximum < hp -> 0.0
        minimum >= hp || maximum <= minimum -> 1.0
        else -> (maximum - hp) / (maximum - minimum)
    }

    private fun selfHeal(details: BattleMoveCandidateView): Double {
        val effects = details.effects?.effects.orEmpty()
        // Rest trades the turns asleep for the heal; it is not a heal the race reads.
        if (effects.any { it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.USER }) return 0.0
        return effects.filter {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER && (it.probability ?: 1.0) >= 1.0
        }.maxOfOrNull { effect -> effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0 } ?: 0.0
    }

    private fun standing(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0
}
