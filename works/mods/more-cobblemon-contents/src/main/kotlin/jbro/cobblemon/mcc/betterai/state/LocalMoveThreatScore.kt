package jbro.cobblemon.mcc.betterai.state

import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
import java.util.concurrent.ConcurrentHashMap

/**
 * How strong an attack is on its own, for picking the attacks an opponent is assumed to carry before it shows them
 * (2026-10-12 user decision, replacing the usage tables): power x accuracy x a secondary-effect factor.
 *
 * The value depends on the move alone, so each move is scored once per server process and kept; a battle only
 * adds the tie-break [typeThreat], which reads the team in front of it.
 */
internal object LocalMoveThreatScore {
    private val scores = ConcurrentHashMap<String, Double>()

    /** Zero for a status move, or one without a fixed power (Seismic Toss and the like). */
    fun of(moveId: String, details: BattleMoveCandidateView): Double =
        // Keyed by what the score reads as well as the id, so a move whose data an addon changed is scored afresh.
        scores.getOrPut("${PublicIds.canonical(moveId)}|${details.typeId}|${details.damageCategory}|${details.power}|${details.accuracy}") {
            compute(moveId, details)
        }

    /**
     * How threatening an attack of [typeId] is to [team]: the sum of its type effectiveness against each living
     * member. Breaks ties between attacks of the same score (Ice Beam against Thunderbolt).
     */
    fun typeThreat(typeId: String, team: List<Set<String>>): Double =
        team.sumOf { types -> StandardTypeEffectiveness.multiplier(typeId, types) }

    private fun compute(moveId: String, details: BattleMoveCandidateView): Double {
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || details.power <= 0.0) return 0.0
        // The same conditions the move inference reads (BattleMoveSetExpectation): an attack that fails unless its
        // condition holds is never assumed, a delayed, first-turn or reply-only one counts half.
        val conditional = jbro.cobblemon.mcc.internal.ai.BattleMoveSetExpectation.factor(moveId, details)
        if (conditional <= 0.0) return 0.0
        val accuracy = (details.accuracy / 100.0).takeIf { it > 0.0 }?.coerceAtMost(1.0) ?: 1.0
        val effects = details.effects?.effects.orEmpty()
        var power = details.power
        var factor = 1.0
        for (effect in effects) {
            val chance = effect.probability ?: 1.0
            val onTarget = effect.target == BattleMoveEffectTarget.SELECTED_TARGET || effect.target == BattleMoveEffectTarget.TARGET_SIDE
            when (effect.kind) {
                BattleMoveEffectKind.MULTI_HIT -> effect.amountRange?.let { hits ->
                    power *= jbro.cobblemon.mcc.internal.ai.BattleMoveSetExpectation.averageHits(hits)
                }
                BattleMoveEffectKind.STATUS -> if (onTarget) factor += chance * STATUS
                BattleMoveEffectKind.VOLATILE_STATUS -> if (onTarget) factor += chance * VOLATILE
                BattleMoveEffectKind.STAT_STAGE -> effect.statStages.values.forEach { stages ->
                    // A drop on the target or a raise on the user helps; a drop on the user (Close Combat) costs.
                    val helps = if (onTarget) stages < 0 else stages > 0
                    factor += chance * kotlin.math.abs(stages) * (if (helps) STAGE else -STAGE_COST)
                }
                BattleMoveEffectKind.DRAIN_FRACTION -> factor += DRAIN * (effect.fractionRange?.maximum ?: 0.5)
                BattleMoveEffectKind.RECOIL_FRACTION -> factor -= RECOIL * (effect.fractionRange?.maximum ?: 0.33)
                BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectKind.SWITCH_TARGET -> factor += PIVOT
                // The share of maximum HP spent is that share of the move's value, as the move inference reads it.
                BattleMoveEffectKind.MAX_HP_RECOIL -> factor -= effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.5
                BattleMoveEffectKind.CRASH_RECOIL -> factor -= CRASH * (1.0 - accuracy)
                BattleMoveEffectKind.CHARGE_TURN, BattleMoveEffectKind.RECHARGE_TURN -> factor *= LOST_TURN
                BattleMoveEffectKind.SELF_DESTRUCT -> factor *= SELF_KNOCKOUT
                else -> Unit
            }
        }
        return power * accuracy * factor.coerceAtLeast(MINIMUM_FACTOR) * conditional
    }

    /** A burn, paralysis, poison, sleep or freeze on the target, per unit of chance. */
    private const val STATUS = 0.3
    /** A flinch or confusion on the target, per unit of chance. */
    private const val VOLATILE = 0.2
    /** Each stage dropped on the target or raised on the user, per unit of chance. */
    private const val STAGE = 0.1
    /** Each stage the user loses (Close Combat, Draco Meteor). */
    private const val STAGE_COST = 0.05
    /** Per share of the damage healed back. */
    private const val DRAIN = 0.3
    /** Per share of the damage taken back. */
    private const val RECOIL = 0.3
    /** A move that switches the user out or the target away. */
    private const val PIVOT = 0.1
    /** A move that costs another turn (Solar Beam's charge, Hyper Beam's recharge). */
    private const val LOST_TURN = 0.5
    /** A move that knocks its user out (Explosion, Self-Destruct): rarely the attack a set is built around. */
    private const val SELF_KNOCKOUT = 0.25
    /** Per share of misses, for a move that hurts its user when it misses (High Jump Kick). */
    private const val CRASH = 0.5
    private const val MINIMUM_FACTOR = 0.1
}
