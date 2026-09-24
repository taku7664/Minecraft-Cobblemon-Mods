package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.*

/**
 * Ranks a learnset attack as a set-level expectation, not as a turn-specific action choice.
 * All weights live here so inference policy and expectation balance remain separate.
 */
internal object BattleOpponentMoveExpectationScorer {
    fun score(
        pokemon: BattlePokemonStateView,
        move: BattleMoveCandidateView,
        format: BattleFormat,
    ): Double {
        if (move.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0

        val hitProbability = (move.accuracy / 100.0).coerceIn(0.0, 1.0)
        val sameTypeBonus = if (pokemon.knownTypeIds.any { sameId(it, move.typeId) }) STAB else 1.0
        val baseDamage = move.power * hitProbability * sameTypeBonus *
            categoryFit(pokemon, move.damageCategory) * spreadValue(move.targetPattern, format)
        val secondaryValue = move.effects?.effects.orEmpty().sumOf { effect ->
            effectValue(effect) * (effect.probability ?: 1.0) * hitProbability
        }
        val tempoPenalty = move.effects?.effects.orEmpty().sumOf { effect ->
            when (effect.kind) {
                BattleMoveEffectKind.CHARGE_TURN -> baseDamage * CHARGE_PENALTY
                BattleMoveEffectKind.RECHARGE_TURN -> baseDamage * RECHARGE_PENALTY
                BattleMoveEffectKind.SELF_DESTRUCT -> baseDamage * SELF_DESTRUCT_PENALTY
                else -> 0.0
            }
        }
        return baseDamage + secondaryValue - tempoPenalty + move.priority.coerceAtLeast(0) * PRIORITY_WEIGHT
    }

    private fun categoryFit(
        pokemon: BattlePokemonStateView,
        category: BattleMoveDamageCategory,
    ): Double {
        val stats = pokemon.combatStats ?: return 1.0
        val attack = stats.attack.midpoint()
        val specialAttack = stats.specialAttack.midpoint()
        val strongest = maxOf(attack, specialAttack).coerceAtLeast(1.0)
        val selected = when (category) {
            BattleMoveDamageCategory.PHYSICAL -> attack
            BattleMoveDamageCategory.SPECIAL -> specialAttack
            BattleMoveDamageCategory.STATUS -> strongest
        }
        return (selected / strongest).coerceIn(MINIMUM_CATEGORY_FIT, 1.0)
    }

    private fun spreadValue(pattern: BattleMoveTargetPattern, format: BattleFormat): Double =
        if (format == BattleFormat.DOUBLE && pattern == BattleMoveTargetPattern.ALL_OPPONENTS) {
            DOUBLES_ALL_OPPONENTS_VALUE
        } else {
            1.0
        }

    private fun effectValue(effect: BattleMoveEffectView): Double = when (effect.kind) {
        BattleMoveEffectKind.STAT_STAGE -> stageValue(effect)
        BattleMoveEffectKind.STATUS -> directionalValue(effect.target, STATUS_VALUE)
        BattleMoveEffectKind.VOLATILE_STATUS -> directionalValue(effect.target, VOLATILE_VALUE)
        BattleMoveEffectKind.HEAL_FRACTION ->
            recoveryValue(effect.target, effect.fractionRange?.midpoint().orZero() * HEAL_FRACTION_VALUE)
        BattleMoveEffectKind.DRAIN_FRACTION ->
            effect.fractionRange?.midpoint().orZero() * DRAIN_FRACTION_VALUE
        BattleMoveEffectKind.RECOIL_FRACTION,
        BattleMoveEffectKind.CRASH_RECOIL,
        BattleMoveEffectKind.MAX_HP_RECOIL,
        BattleMoveEffectKind.STRUGGLE_RECOIL,
        -> -effect.fractionRange?.midpoint().orDefault(DEFAULT_RECOIL_FRACTION) * RECOIL_FRACTION_VALUE
        BattleMoveEffectKind.SWITCH_TARGET -> directionalValue(effect.target, FORCED_SWITCH_VALUE)
        BattleMoveEffectKind.SWITCH_USER -> PIVOT_VALUE
        else -> 0.0
    }

    private fun stageValue(effect: BattleMoveEffectView): Double {
        val signedStages = effect.statStages.values.sum()
        return when (effect.target) {
            BattleMoveEffectTarget.USER,
            BattleMoveEffectTarget.USER_SIDE,
            -> signedStages * STAT_STAGE_VALUE
            BattleMoveEffectTarget.SELECTED_TARGET,
            BattleMoveEffectTarget.TARGET_SIDE,
            -> -signedStages * STAT_STAGE_VALUE
            BattleMoveEffectTarget.FIELD -> 0.0
        }
    }

    private fun directionalValue(target: BattleMoveEffectTarget, magnitude: Double): Double = when (target) {
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.USER_SIDE,
        -> -magnitude
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.TARGET_SIDE,
        -> magnitude
        BattleMoveEffectTarget.FIELD -> 0.0
    }

    private fun recoveryValue(target: BattleMoveEffectTarget, magnitude: Double): Double = when (target) {
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.USER_SIDE,
        -> magnitude
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.TARGET_SIDE,
        -> -magnitude
        BattleMoveEffectTarget.FIELD -> 0.0
    }

    private fun BattleIntegerRange.midpoint(): Double = (minimum + maximum) / 2.0
    private fun BattleFractionRange.midpoint(): Double = (minimum + maximum) / 2.0
    private fun Double?.orZero(): Double = this ?: 0.0
    private fun Double?.orDefault(default: Double): Double = this ?: default
    private fun sameId(left: String, right: String): Boolean = canonical(left) == canonical(right)
    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val STAB = 1.5
    private const val MINIMUM_CATEGORY_FIT = 0.65
    /** Two targets at the native 0.75 spread modifier. */
    private const val DOUBLES_ALL_OPPONENTS_VALUE = 1.5
    private const val PRIORITY_WEIGHT = 12.0
    private const val STAT_STAGE_VALUE = 50.0
    private const val STATUS_VALUE = 40.0
    private const val VOLATILE_VALUE = 20.0
    private const val HEAL_FRACTION_VALUE = 40.0
    private const val DRAIN_FRACTION_VALUE = 25.0
    private const val RECOIL_FRACTION_VALUE = 60.0
    private const val DEFAULT_RECOIL_FRACTION = 1.0 / 3.0
    private const val FORCED_SWITCH_VALUE = 15.0
    private const val PIVOT_VALUE = 8.0
    private const val CHARGE_PENALTY = 0.50
    private const val RECHARGE_PENALTY = 0.50
    private const val SELF_DESTRUCT_PENALTY = 0.80
}
