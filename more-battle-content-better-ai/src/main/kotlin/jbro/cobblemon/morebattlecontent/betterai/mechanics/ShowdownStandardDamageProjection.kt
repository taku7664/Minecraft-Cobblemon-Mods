package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleDamageFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import kotlin.math.ceil
import kotlin.math.floor

internal data class ShowdownStandardDamageProjectionResult(
    val minimumDamage: Int,
    val maximumDamage: Int,
    val minimumHypothesisRolls: List<Int>,
    val maximumHypothesisRolls: List<Int>,
    val damageFractionRange: BattleDamageFractionRange,
    val koProbabilityRange: BattleFractionRange,
    val knockoutAssessment: BattleKnockoutAssessment,
) {
    /**
     * The same hit against a defender that cannot be knocked out by it.
     *
     * Damage still lands - a Sash user drops to one health, not to none - so only the knockout half is
     * rewritten. The reported fraction is capped at what actually leaves the defender alive, because a
     * reader that trusted the raw number would still conclude the target is gone.
     */
    fun withoutKnockout(target: BattlePokemonStateView): ShowdownStandardDamageProjectionResult {
        if (knockoutAssessment == BattleKnockoutAssessment.IMPOSSIBLE) return this
        val survivingFraction = (target.hpFraction - oneHealthFraction(target)).coerceAtLeast(0.0)
        return copy(
            damageFractionRange = BattleDamageFractionRange(
                minimum = minOf(damageFractionRange.minimum, survivingFraction),
                maximum = minOf(damageFractionRange.maximum, survivingFraction),
            ),
            koProbabilityRange = BattleFractionRange(0.0, 0.0),
            knockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
        )
    }

    private fun oneHealthFraction(target: BattlePokemonStateView): Double {
        val maximumHp = target.combatStats?.maxHp?.maximum?.takeIf { it > 0 } ?: return 0.0
        return 1.0 / maximumHp
    }
}

/**
 * Pure projection of the Gen 9 Showdown base single-target damage path.
 *
 * It intentionally excludes ability, held-item, weather, field, mechanic, and move-specific
 * callbacks. Callers must retain those exclusions as explicit unknowns.
 */
internal object ShowdownStandardDamageProjection {
    private val randomRolls = 85..100

    fun project(
        level: Int,
        power: Int,
        attack: BattleIntegerRange,
        defence: BattleIntegerRange,
        targetMaxHp: BattleIntegerRange,
        targetHpFraction: Double,
        stab: Double,
        typeMultiplier: Double,
        guaranteedCritical: Boolean = false,
        /**
         * Gen 9 reduction for a move that actually lands on more than one target.
         *
         * Kept separate from [typeMultiplier] on purpose. That parameter is contractually a type-chart
         * value and is checked against the chart below, so folding the reduction into it would both
         * break the check and misreport the move's effectiveness to anything reading it back.
         */
        spreadMultiplier: Double = 1.0,
    ): ShowdownStandardDamageProjectionResult {
        require(level > 0)
        require(power > 0)
        require(targetHpFraction.isFinite() && targetHpFraction in 0.0..1.0)
        require(stab == 1.0 || stab == 1.5)
        require(typeMultiplier in setOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0))
        require(spreadMultiplier == 1.0 || spreadMultiplier == 0.75)

        val minimumRolls = rolls(
            level, power, attack.minimum, defence.maximum, stab, typeMultiplier, guaranteedCritical,
            spreadMultiplier,
        )
        val maximumRolls = rolls(
            level, power, attack.maximum, defence.minimum, stab, typeMultiplier, guaranteedCritical,
            spreadMultiplier,
        )
        val minimumDamage = minimumRolls.min()
        val maximumDamage = maximumRolls.max()
        val minimumKoProbability = knockoutProbability(
            minimumRolls,
            currentHp(targetMaxHp.maximum, targetHpFraction),
        )
        val maximumKoProbability = knockoutProbability(
            maximumRolls,
            currentHp(targetMaxHp.minimum, targetHpFraction),
        )
        val assessment = when {
            minimumKoProbability == 1.0 -> BattleKnockoutAssessment.GUARANTEED
            maximumKoProbability == 0.0 -> BattleKnockoutAssessment.IMPOSSIBLE
            else -> BattleKnockoutAssessment.POSSIBLE
        }
        return ShowdownStandardDamageProjectionResult(
            minimumDamage = minimumDamage,
            maximumDamage = maximumDamage,
            minimumHypothesisRolls = minimumRolls,
            maximumHypothesisRolls = maximumRolls,
            damageFractionRange = BattleDamageFractionRange(
                minimum = minimumDamage.toDouble() / targetMaxHp.maximum,
                maximum = maximumDamage.toDouble() / targetMaxHp.minimum,
            ),
            koProbabilityRange = BattleFractionRange(minimumKoProbability, maximumKoProbability),
            knockoutAssessment = assessment,
        )
    }

    private fun rolls(
        level: Int,
        power: Int,
        attack: Int,
        defence: Int,
        stab: Double,
        typeMultiplier: Double,
        guaranteedCritical: Boolean,
        spreadMultiplier: Double,
    ): List<Int> {
        val levelFactor = 2L * level / 5L + 2L
        val unreducedBaseDamage = (((levelFactor * power * attack) / defence) / 50L).toInt() + 2
        // Showdown applies the spread reduction to the base damage, ahead of the critical, random and
        // STAB steps, so it is applied here rather than to the finished roll.
        val baseDamage = if (spreadMultiplier == 0.75) {
            showdownModify(unreducedBaseDamage, 3, 4)
        } else {
            unreducedBaseDamage
        }
        return randomRolls.map { randomRoll ->
            var damage = if (guaranteedCritical) showdownModify(baseDamage, 3, 2) else baseDamage
            damage = damage * randomRoll / 100
            damage = showdownModify(damage, if (stab == 1.5) 3 else 1, if (stab == 1.5) 2 else 1)
            damage = applyTypeMultiplier(damage, typeMultiplier)
            if (typeMultiplier == 0.0) 0 else damage.coerceAtLeast(1)
        }
    }

    private fun showdownModify(value: Int, numerator: Int, denominator: Int): Int {
        val modifier = floor(numerator.toDouble() * 4096.0 / denominator).toLong()
        return ((value.toLong() * modifier + 2047L) / 4096L).toInt()
    }

    private fun applyTypeMultiplier(value: Int, multiplier: Double): Int = when (multiplier) {
        0.0 -> 0
        0.25 -> value / 2 / 2
        0.5 -> value / 2
        1.0 -> value
        2.0 -> value * 2
        4.0 -> value * 2 * 2
        else -> error("Unsupported standard type multiplier: $multiplier")
    }

    private fun currentHp(maxHp: Int, fraction: Double): Int = when {
        fraction == 0.0 -> 0
        else -> ceil(maxHp * fraction).toInt().coerceIn(1, maxHp)
    }

    private fun knockoutProbability(rolls: List<Int>, currentHp: Int): Double =
        if (currentHp == 0) 1.0 else rolls.count { it >= currentHp }.toDouble() / rolls.size
}
