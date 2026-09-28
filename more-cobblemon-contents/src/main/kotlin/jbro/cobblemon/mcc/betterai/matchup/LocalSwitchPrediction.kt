package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * An attack aimed at a Pokemon its trainer is expected to switch out is worth what it does to the one coming in.
 *
 * The root heuristic prices an attack against the Pokemon in front of it, and it outweighs the search's
 * correction, so a switch the opponent keeps making never moved the choice: the AI clicked the super effective
 * Close Combat into the Ghost that came in on it every time. With a switch expected (from [LocalOpponentRepeats],
 * evidence only), each single-target attack moves by the expected change of its value, scored as the intent
 * predictor scores an attack: [KNOCKOUT_WEIGHT] of its knockout chance and [DAMAGE_WEIGHT] of the HP share it takes.
 */
internal object LocalSwitchPrediction {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        expected: Map<UUID, Map<UUID, Double>>,
    ): Map<String, Double> {
        if (expected.isEmpty()) return emptyMap()
        val state = context.state
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE || part.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) continue
                val slot = part.targets.singleOrNull()?.takeIf { it.side == BattleSide.OPPONENT } ?: continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
                } ?: continue
                val target = state.pokemon.firstOrNull {
                    it.side == BattleSide.OPPONENT && it.activeSlot == slot.slot && !it.fainted && it.hpFraction > 0.0
                } ?: continue
                val switches = expected[target.battlePokemonId] ?: continue
                val moveId = PublicIds.canonical(part.moveId ?: continue)
                val now = value(scores, user.battlePokemonId, moveId, target.battlePokemonId, target.hpFraction) ?: continue
                for ((incomingId, chance) in switches) {
                    val incoming = state.pokemon.firstOrNull { it.battlePokemonId == incomingId && !it.fainted && it.hpFraction > 0.0 } ?: continue
                    // A pair the scores cover but without this move: the move does nothing there (an immunity).
                    val then = value(scores, user.battlePokemonId, moveId, incomingId, incoming.hpFraction)
                        ?: if (scores.pokemon(user.battlePokemonId, incomingId) != null) 0.0 else continue
                    change += chance * (then - now)
                }
            }
            if (change != 0.0) out[candidate.actionId] = change * SCORE_SCALE
        }
        return out
    }

    private fun value(scores: MatchupScores, userId: UUID, moveId: String, targetId: UUID, targetHp: Double): Double? {
        val score = scores.moves(userId, targetId).firstOrNull { it.moveId == moveId } ?: return null
        val share = score.accuracy * (score.minimumDamageFraction + score.maximumDamageFraction) / 2.0 / targetHp.coerceAtLeast(MINIMUM_HP)
        return KNOCKOUT_WEIGHT * score.knockoutChanceWithin(1) + DAMAGE_WEIGHT * share.coerceAtMost(1.0)
    }

    const val REASON = "switch_prediction"
    private const val KNOCKOUT_WEIGHT = 0.6
    private const val DAMAGE_WEIGHT = 0.4
    private const val MINIMUM_HP = 0.05
    /** One point of attack value is one HP bar of score, as the switching rules count. */
    private const val SCORE_SCALE = LocalSwitchRules.SCORE_SCALE
}
