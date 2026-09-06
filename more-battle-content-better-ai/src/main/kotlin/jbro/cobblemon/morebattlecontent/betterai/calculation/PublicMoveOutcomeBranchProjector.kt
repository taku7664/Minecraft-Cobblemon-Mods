package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAccuracy
import kotlin.math.pow

/** A narrow, event-free chance model for one attempted move. */
internal data class PublicMoveOutcomeBranch(
    val probability: Double,
    val hit: Boolean,
    val damageFraction: Double,
)

internal data class PublicDamageRollSummary(
    val damageFraction: Double,
    val knockoutProbability: Double,
)

internal object PublicMoveOutcomeBranchProjector {
    fun project(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): List<PublicMoveOutcomeBranch> {
        val accuracy = LocalPublicAccuracy.probability(candidate, context, actingSide)
        val calculatedRolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(
            candidate,
            context,
            actingSide,
        )
        val rolls = calculatedRolls ?: fallbackDamageOutcomes(candidate, actingSide)
        val defaultTargetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val explicitTarget = candidate.targets.singleOrNull()
        val targetHp = if (explicitTarget != null) {
            context.state.pokemon.firstOrNull {
                it.side == explicitTarget.side && it.activeSlot == explicitTarget.slot && !it.fainted && it.hpFraction > 0.0
            }?.hpFraction
        } else {
            context.state.pokemon.singleOrNull {
                it.side == defaultTargetSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }?.hpFraction
        }
        if (LocalDeclaredMultiHit.usesPerHitAccuracy(candidate)) {
            return perHitAccuracyBranches(candidate, accuracy, rolls, targetHp)
        }
        val hitBranches = damageBranches(rolls, targetHp, accuracy)
        val miss = if (accuracy < 1.0) {
            listOf(PublicMoveOutcomeBranch(1.0 - accuracy, hit = false, damageFraction = 0.0))
        } else {
            emptyList()
        }
        return (miss + hitBranches).filter { it.probability > 0.0 }
    }

    private fun perHitAccuracyBranches(
        candidate: BattleActionCandidate,
        accuracy: Double,
        damageRolls: List<Double>,
        targetHp: Double?,
    ): List<PublicMoveOutcomeBranch> {
        val maximum = LocalDeclaredMultiHit.maximumCount(candidate)
        val branches = mutableListOf(PublicMoveOutcomeBranch(1.0 - accuracy, false, 0.0))
        for (hits in 1 until maximum) {
            val probability = accuracy.pow(hits) * (1.0 - accuracy)
            branches += damageBranches(damageRolls.map { it * hits }, targetHp, probability)
        }
        branches += damageBranches(damageRolls.map { it * maximum }, targetHp, accuracy.pow(maximum))
        return branches.filter { it.probability > 0.0 }
    }

    /**
     * KO and survival must reach distinct states: later actions can disappear or change target.
     * Keep one real roll within each class, retaining the existing compact model for other HP
     * thresholds. Direct-hit mechanics subsequently resolve Sash, Sturdy, Disguise and healing;
     * removal value comes from those resulting states, never an additional raw-roll KO bonus.
     */
    private fun damageBranches(
        rolls: List<Double>,
        targetHp: Double?,
        probability: Double,
    ): List<PublicMoveOutcomeBranch> {
        if (rolls.isEmpty()) return listOf(PublicMoveOutcomeBranch(probability, true, 0.0))
        return rolls.groupBy { targetHp != null && it >= targetHp }.values.map { group ->
            PublicMoveOutcomeBranch(
                probability * group.size / rolls.size,
                true,
                summarizeDamageRolls(group, targetHp).damageFraction,
            )
        }
    }

    /**
     * Keeps one mechanically possible representative roll for recursion. With an even roll count,
     * the lower middle value is used so the projected state never invents an averaged damage roll.
     */
    fun summarizeDamageRolls(
        rolls: List<Double>,
        targetHpFraction: Double?,
    ): PublicDamageRollSummary {
        if (rolls.isEmpty()) return PublicDamageRollSummary(0.0, 0.0)
        val sorted = rolls.sorted()
        val knockoutProbability = targetHpFraction?.takeIf { it > 0.0 }?.let { hp ->
            sorted.count { damage -> damage + DAMAGE_EPSILON >= hp }.toDouble() / sorted.size
        } ?: 0.0
        return PublicDamageRollSummary(
            damageFraction = sorted[(sorted.size - 1) / 2],
            knockoutProbability = knockoutProbability,
        )
    }

    private fun fallbackDamageOutcomes(
        candidate: BattleActionCandidate,
        actingSide: BattleSide,
    ): List<Double> {
        if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) return listOf(0.0)
        val range = candidate.facts?.standardDamageFractionRange ?: return listOf(0.0)
        return listOf(if (actingSide == BattleSide.ALLY) range.minimum else range.maximum)
    }

    private const val DAMAGE_EPSILON = 1e-9
}
