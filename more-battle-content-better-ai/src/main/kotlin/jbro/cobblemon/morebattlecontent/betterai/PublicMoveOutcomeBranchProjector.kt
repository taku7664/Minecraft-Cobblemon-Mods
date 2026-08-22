package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/** A narrow, event-free chance model for one attempted move. */
internal data class PublicMoveOutcomeBranch(
    val probability: Double,
    val hit: Boolean,
    val damageFraction: Double,
)

internal object PublicMoveOutcomeBranchProjector {
    fun project(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<PublicMoveOutcomeBranch> {
        val accuracy = candidate.facts?.baseAccuracyProbability
            ?: candidate.moveDetails?.accuracy?.div(100.0)?.coerceIn(0.0, 1.0)
            ?: 0.0
        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(
            candidate,
            context,
            actingSide,
        ) ?: fallbackDamageOutcomes(candidate, actingSide)
        val hitBranches = rolls.groupingBy { it }.eachCount().map { (damage, count) ->
            PublicMoveOutcomeBranch(
                probability = accuracy * count.toDouble() / rolls.size,
                hit = true,
                damageFraction = damage,
            )
        }
        val miss = if (accuracy < 1.0) {
            listOf(PublicMoveOutcomeBranch(1.0 - accuracy, hit = false, damageFraction = 0.0))
        } else {
            emptyList()
        }
        return (miss + hitBranches).filter { it.probability > 0.0 }
    }

    private fun fallbackDamageOutcomes(
        candidate: BattleActionCandidate,
        actingSide: BattleSide,
    ): List<Double> {
        if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return listOf(0.0)
        val range = candidate.facts?.standardDamageFractionRange ?: return listOf(0.0)
        return listOf(if (actingSide == BattleSide.ALLY) range.minimum else range.maximum)
    }
}
