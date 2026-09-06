package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import kotlin.math.roundToLong

/** Preserves exact public integer HP without rounding modeled fractional expectations. */
internal object LocalHpArithmetic {
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
