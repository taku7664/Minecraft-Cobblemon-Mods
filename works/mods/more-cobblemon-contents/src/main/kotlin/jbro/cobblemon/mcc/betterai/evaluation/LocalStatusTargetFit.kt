package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier

/**
 * How well a major status suits the Pokemon it is aimed at, as a multiplier on the legacy status
 * score.
 *
 * The legacy evaluator priced every major status as the same flat pressure: Burn on a special
 * attacker scored like Burn on a physical one, and Toxic into a revealed Poison Heal looked like a
 * good play. A player picks the status for the target: Burn for physical attackers, Paralysis for
 * whatever outspeeds the team, Toxic for what stays in and heals. Public stats, revealed moves and
 * revealed abilities are enough to tell these apart.
 *
 * The fit runs 0.3..1.4 (Burn on a pure special attacker is the low end), and 0 when a revealed ability turns the status against the AI. It is scaled
 * toward 1.0 by tier: an Introductory trainer does not look, Standard half, Advanced and Boss fully.
 * The native search simulates the real effect inside its horizon and does not use this.
 */
internal object LocalStatusTargetFit {
    fun scale(tier: BattleTrainerTier): Double = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> 0.0
        BattleTrainerTier.STANDARD -> 0.5
        BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS -> 1.0
    }

    fun multiplier(
        statusId: String?,
        target: BattlePokemonStateView?,
        context: BattleDecisionContext,
        scale: Double,
    ): Double {
        if (scale <= 0.0 || statusId == null || target == null) return 1.0
        val raw = rawFit(canonical(statusId), target, context) ?: return 1.0
        return 1.0 + (raw - 1.0) * scale.coerceIn(0.0, 1.0)
    }

    private fun rawFit(status: String, target: BattlePokemonStateView, context: BattleDecisionContext): Double? {
        val base = when (status) {
            // Against a special attacker Burn is only its 1/16 a turn, half of Poison's chip; the
            // attack halving is the rest of its value.
            BURN -> BURN_CHIP_FIT + (MAXIMUM_FIT - BURN_CHIP_FIT) * physicalShare(target, context)
            PARALYSIS -> 0.7 + 0.7 * outspeedShare(target, context)
            TOXIC -> staying(target, context)
            POISON -> 1.0 + (staying(target, context) - 1.0) * 0.5
            SLEEP -> SLEEP_FIT
            FREEZE -> 1.0
            else -> return null
        }
        return (base * abilityFactor(status, target)).coerceIn(0.0, MAXIMUM_FIT)
    }

    /** 0..1: how much of the target's offence is physical, from public stats and revealed moves. */
    private fun physicalShare(target: BattlePokemonStateView, context: BattleDecisionContext): Double {
        val statShare = target.combatStats?.let { stats ->
            val attack = midpoint(stats.attack)
            val special = midpoint(stats.specialAttack)
            if (attack + special > 0.0) attack / (attack + special) else null
        }
        val damaging = context.publicActionCatalog.forPokemon(target.battlePokemonId).filter {
            it.details.damageCategory != BattleMoveDamageCategory.STATUS && it.details.power > 0.0
        }
        val moveShare = damaging.takeIf { it.isNotEmpty() }?.let { moves ->
            moves.count { it.details.damageCategory == BattleMoveDamageCategory.PHYSICAL }.toDouble() / moves.size
        }
        return when {
            statShare != null && moveShare != null -> (statShare + moveShare) / 2.0
            else -> statShare ?: moveShare ?: 0.5
        }
    }

    /** 0..1: share of the AI's living Pokemon the target outspeeds; overlapping ranges count half. */
    private fun outspeedShare(target: BattlePokemonStateView, context: BattleDecisionContext): Double {
        val targetSpeed = target.combatStats?.speed ?: return 0.5
        val allies = context.state.pokemon.filter { it.side == BattleSide.ALLY && !it.fainted && it.hpFraction > 0.0 }
        val compared = allies.mapNotNull { ally ->
            val allySpeed = ally.combatStats?.speed ?: return@mapNotNull null
            when {
                targetSpeed.minimum > allySpeed.maximum -> 1.0
                targetSpeed.maximum < allySpeed.minimum -> 0.0
                else -> 0.5
            }
        }
        return if (compared.isEmpty()) 0.5 else compared.average()
    }

    /** Toxic fit: higher for a bulky Pokemon that heals, lower for one that pivots out. */
    private fun staying(target: BattlePokemonStateView, context: BattleDecisionContext): Double {
        var fit = 1.0
        val moves = context.publicActionCatalog.forPokemon(target.battlePokemonId)
        val heals = moves.any { move ->
            move.details.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
            }
        } || canonical(target.knownHeldItemId) in RESIDUAL_HEALING_ITEMS
        if (heals) fit += 0.2
        if (target.combatStats?.let(::bulky) == true) fit += 0.2
        val pivots = moves.any { canonical(it.moveId) in PIVOT_MOVES } || canonical(target.knownAbilityId) == "regenerator"
        if (pivots) fit -= 0.3
        return fit.coerceIn(MINIMUM_FIT, MAXIMUM_FIT)
    }

    private fun bulky(stats: BattleCombatStatRangesView): Boolean =
        midpoint(stats.defence) + midpoint(stats.specialDefence) > midpoint(stats.attack) + midpoint(stats.specialAttack)

    /** Revealed abilities that turn the status against the AI, or wash it off. */
    private fun abilityFactor(status: String, target: BattlePokemonStateView): Double =
        when (canonical(target.knownAbilityId)) {
            "guts", "marvelscale", "quickfeet" -> 0.0
            "poisonheal", "toxicboost" -> if (status == TOXIC || status == POISON) 0.0 else 1.0
            "flareboost" -> if (status == BURN) 0.0 else 1.0
            "synchronize" -> if (status == SLEEP || status == FREEZE) 1.0 else 0.5
            "naturalcure" -> 0.6
            "shedskin" -> 0.7
            "earlybird" -> if (status == SLEEP) 0.6 else 1.0
            else -> 1.0
        }

    private fun midpoint(range: BattleIntegerRange): Double = (range.minimum + range.maximum) / 2.0

    private fun canonical(value: String?): String =
        PublicIds.canonical(value.orEmpty())

    private const val BURN = "brn"
    private const val PARALYSIS = "par"
    private const val TOXIC = "tox"
    private const val POISON = "psn"
    private const val SLEEP = "slp"
    private const val FREEZE = "frz"
    private const val SLEEP_FIT = 1.2
    private const val BURN_CHIP_FIT = 0.3
    private const val MINIMUM_FIT = 0.6
    private const val MAXIMUM_FIT = 1.4
    private val RESIDUAL_HEALING_ITEMS = setOf("leftovers", "blacksludge")
    private val PIVOT_MOVES = setOf("uturn", "voltswitch", "flipturn", "partingshot", "teleport", "chillyreception", "shedtail")
}
