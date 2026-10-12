package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategory

/**
 * One global weight per kind of action on its final value: attacks, status moves, switches, and inside the status
 * moves one per kind (a pure self stat raise, recovery, protection, ...), multiplied into the status weight.
 *
 * The AI plays a status move one turn at a time and rarely follows it up (Yawn, then the switch it forces; Trick
 * Room, then the slow attacker), so a status move it prices well still pays less than the price says. The weight
 * shrinks the kind's value toward worse whatever its sign: a positive value is multiplied by the weight, a negative
 * one divided by it (multiplying -50 by 0.8 would have made it better).
 */
internal object LocalActionKindWeights {
    fun apply(ranked: List<LocalBattleActionRank>, tuning: LocalDecisionTuning): List<LocalBattleActionRank> {
        if (ranked.all { weight(it, tuning) == 1.0 }) return ranked
        return LocalBattleActionPolicy.sort(ranked.map { rank ->
            val weight = weight(rank, tuning)
            if (weight == 1.0) rank else {
                val value = rank.comparisonValue
                rank.copy(comparisonValue = if (value >= 0.0) value * weight else value / weight)
            }
        })
    }

    /** The weight inside the status moves, by the kind BattleStatusMoveCategories reads from the move. */
    private fun statusKindWeight(candidate: jbro.cobblemon.mcc.internal.ai.BattleActionCandidate, tuning: LocalDecisionTuning): Double {
        val details = candidate.moveDetails ?: return 1.0
        if (BattleStatusMoveCategories.isPureSelfSetup(details)) return tuning.setupMoveWeight
        return when (BattleStatusMoveCategories.classify(candidate.moveId.orEmpty(), details)) {
            BattleStatusMoveCategory.RECOVERY -> tuning.recoveryMoveWeight
            BattleStatusMoveCategory.PROTECTION -> tuning.protectionMoveWeight
            BattleStatusMoveCategory.STATUS_INFLICTION -> tuning.statusInflictionMoveWeight
            BattleStatusMoveCategory.DISRUPTION -> tuning.disruptionMoveWeight
            BattleStatusMoveCategory.FIELD -> tuning.fieldMoveWeight
            BattleStatusMoveCategory.HAZARD -> tuning.hazardMoveWeight
            BattleStatusMoveCategory.OTHER_STATUS, null -> tuning.otherStatusMoveWeight
        }
    }

    private fun weight(rank: LocalBattleActionRank, tuning: LocalDecisionTuning): Double {
        val candidate = rank.outcome.candidate
        return when (candidate.kind) {
            BattleActionKind.SWITCH -> tuning.switchWeight
            BattleActionKind.USE_MOVE -> {
                val details = candidate.moveDetails
                if (details?.damageCategory == BattleMoveDamageCategory.STATUS) tuning.statusMoveWeight * statusKindWeight(candidate, tuning)
                else tuning.attackMoveWeight
            }
            else -> 1.0
        }
    }
}
