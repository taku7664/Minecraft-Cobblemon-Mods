package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory

/**
 * One global weight per kind of action on its final value: attacks, status moves, switches.
 *
 * The AI plays a status move one turn at a time and rarely follows it up (Yawn, then the switch it forces; Trick
 * Room, then the slow attacker), so a status move it prices well still pays less than the price says. The weight
 * shrinks the kind's value toward worse whatever its sign: a positive value is multiplied by the weight, a negative
 * one divided by it (multiplying -50 by 0.8 would have made it better).
 */
internal object LocalActionKindWeights {
    fun apply(ranked: List<LocalBattleActionRank>, tuning: LocalDecisionTuning): List<LocalBattleActionRank> {
        if (tuning.attackMoveWeight == 1.0 && tuning.statusMoveWeight == 1.0 && tuning.switchWeight == 1.0) return ranked
        return LocalBattleActionPolicy.sort(ranked.map { rank ->
            val weight = weight(rank, tuning)
            if (weight == 1.0) rank else {
                val value = rank.comparisonValue
                rank.copy(comparisonValue = if (value >= 0.0) value * weight else value / weight)
            }
        })
    }

    private fun weight(rank: LocalBattleActionRank, tuning: LocalDecisionTuning): Double {
        val candidate = rank.outcome.candidate
        return when (candidate.kind) {
            BattleActionKind.SWITCH -> tuning.switchWeight
            BattleActionKind.USE_MOVE ->
                if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) tuning.statusMoveWeight
                else tuning.attackMoveWeight
            else -> 1.0
        }
    }
}
