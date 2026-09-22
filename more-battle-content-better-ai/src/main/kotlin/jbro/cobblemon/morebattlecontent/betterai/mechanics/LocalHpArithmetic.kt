package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Preserves exact public integer HP without rounding modeled fractional expectations. */
internal object LocalHpArithmetic {
    data class ExactHpHypothesis(val current: Int, val maximum: Int)

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
