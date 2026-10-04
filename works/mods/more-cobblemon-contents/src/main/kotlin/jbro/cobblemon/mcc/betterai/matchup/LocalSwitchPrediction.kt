package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * An attack aimed at a Pokemon its trainer is expected to switch out is worth what it does to the one coming in.
 *
 * The root heuristic prices an attack against the Pokemon in front of it, and it outweighs the search's
 * correction, so a switch the opponent keeps making never moved the choice: the AI clicked the super effective
 * Close Combat into the Ghost that came in on it every time. With a switch expected (from [LocalOpponentRepeats]'
 * evidence, or the predicted switches of [LocalOpponentIntentPredictor]), each attack on that Pokemon moves by the
 * expected change of its value, in the root's own units: the HP it takes on the board and the knockout material.
 * Priced any lower, a predicted switch could never outweigh the knockout the root credits against the one leaving.
 *
 * In singles an attack without a declared target (Earthquake, a spread move) hits the one opponent in front.
 */
internal object LocalSwitchPrediction {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        expected: Map<UUID, Map<UUID, Double>>,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Map<String, Double> {
        if (expected.isEmpty()) return emptyMap()
        val state = context.state
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE || part.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
                } ?: continue
                fun standing(it: BattlePokemonStateView) = it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                val target = if (state.format == BattleFormat.SINGLE) state.pokemon.singleOrNull(::standing) ?: continue else {
                    val slot = part.targets.singleOrNull()?.takeIf { it.side == BattleSide.OPPONENT } ?: continue
                    state.pokemon.firstOrNull { standing(it) && it.activeSlot == slot.slot } ?: continue
                }
                val switches = expected[target.battlePokemonId] ?: continue
                val moveId = PublicIds.canonical(part.moveId ?: continue)
                val now = value(scores, user.battlePokemonId, moveId, target.battlePokemonId, tuning) ?: continue
                for ((incomingId, chance) in switches) {
                    val incoming = state.pokemon.firstOrNull { it.battlePokemonId == incomingId && !it.fainted && it.hpFraction > 0.0 } ?: continue
                    // A pair the scores cover but without this move: the move does nothing there (an immunity).
                    val then = value(scores, user.battlePokemonId, moveId, incomingId, tuning)
                        ?: if (scores.pokemon(user.battlePokemonId, incomingId) != null) 0.0 else continue
                    change += chance * (then - now)
                }
            }
            if (change != 0.0) out[candidate.actionId] = change
        }
        return out
    }

    /** An attack's value as the root prices it: the expected HP taken (capped at what is left) and the knockout. */
    private fun value(scores: MatchupScores, userId: UUID, moveId: String, targetId: UUID, tuning: LocalDecisionTuning): Double? {
        val score = scores.moves(userId, targetId).firstOrNull { it.moveId == moveId } ?: return null
        val taken = score.accuracy * (score.minimumDamageFraction + score.maximumDamageFraction) / 2.0
        return tuning.board(taken) + tuning.knockoutMaterialScore * score.knockoutChanceWithin(1)
    }

    const val REASON = "switch_prediction"
}
