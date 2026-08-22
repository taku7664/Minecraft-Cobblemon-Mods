package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDamageFractionRange
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
)

/**
 * Pure projection of the Gen 9 Showdown base, non-critical, single-target damage path.
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
    ): ShowdownStandardDamageProjectionResult {
        require(level > 0)
        require(power > 0)
        require(targetHpFraction.isFinite() && targetHpFraction in 0.0..1.0)
        require(stab == 1.0 || stab == 1.5)
        require(typeMultiplier in setOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0))

        val minimumRolls = rolls(level, power, attack.minimum, defence.maximum, stab, typeMultiplier)
        val maximumRolls = rolls(level, power, attack.maximum, defence.minimum, stab, typeMultiplier)
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
    ): List<Int> {
        val levelFactor = 2L * level / 5L + 2L
        val baseDamage = (((levelFactor * power * attack) / defence) / 50L).toInt() + 2
        return randomRolls.map { randomRoll ->
            var damage = baseDamage * randomRoll / 100
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
