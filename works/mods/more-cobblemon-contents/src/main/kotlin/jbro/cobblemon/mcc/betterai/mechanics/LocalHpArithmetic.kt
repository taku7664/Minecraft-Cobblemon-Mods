package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.floor

/** Preserves exact public integer HP without rounding modeled fractional expectations. */
internal object LocalHpArithmetic {
    data class ExactHpHypothesis(val current: Int, val maximum: Int)

    /** Heal an integer share of baseMaxhp; the current bar may be doubled by Dynamax.
     * Preserve fractional modeled HP, and average only maxima the public range permits.
     */
    fun healBaseShare(pokemon: BattlePokemonStateView, divisor: Int): Double {
        require(divisor > 0)
        val share = LocalDynamax.damageShare(pokemon)
        val maxima = pokemon.combatStats?.maxHp
            ?: return (pokemon.hpFraction + share / divisor).coerceAtMost(1.0)
        return (maxima.minimum..maxima.maximum).map { base ->
            val maximum = base / share
            val current = pokemon.hpFraction * maximum
            val rounded = current.roundToLong()
            val exact = if (rounded / maximum == pokemon.hpFraction) rounded.toDouble() else current
            ((exact + floor(base.toDouble() / divisor).coerceAtLeast(1.0)) / maximum).coerceAtMost(1.0)
        }.average()
    }

    /** Every integer HP pair in the public maximum-HP range that exactly round-trips the public ratio. */
    fun exactHpHypotheses(pokemon: BattlePokemonStateView): List<ExactHpHypothesis> {
        val maxHp = pokemon.combatStats?.maxHp ?: return emptyList()
        return (maxHp.minimum..maxHp.maximum).mapNotNull { maximum ->
            val current = (pokemon.hpFraction * maximum).roundToInt()
            ExactHpHypothesis(current, maximum).takeIf {
                current in 0..maximum && current.toDouble() / maximum == pokemon.hpFraction
            }
        }
    }

    fun change(pokemon: BattlePokemonStateView, hpFraction: Double, change: Double): Double {
        val maxHp = pokemon.combatStats?.maxHp
        if (maxHp != null && maxHp.minimum == maxHp.maximum) {
            val maximum = maxHp.minimum.toDouble()
            val currentHp = (hpFraction * maximum).roundToLong()
            val delta = (change * maximum).roundToLong()
            // Both operands must round-trip exactly; an epsilon band would erase legitimate
            // fractional expectations. A public range does not reveal the hidden maximum HP.
            if (currentHp / maximum == hpFraction && delta / maximum == change) {
                return (currentHp + delta) / maximum
            }
        }
        return hpFraction + change
    }
}
