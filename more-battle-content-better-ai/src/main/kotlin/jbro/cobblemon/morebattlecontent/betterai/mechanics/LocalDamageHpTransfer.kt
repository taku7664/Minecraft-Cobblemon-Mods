package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import kotlin.math.roundToLong

/** Converts damage in the target's HP units to a drain/recoil change in the user's units. */
internal object LocalDamageHpTransfer {
    fun fraction(damage: Double, ratio: Double, actor: BattlePokemonStateView?, target: BattlePokemonStateView?, roundActualHit: Boolean = true): Double {
        if (damage <= 0.0 || ratio <= 0.0) return 0.0
        val actorHp = actor?.combatStats?.maxHp
        val targetHp = target?.combatStats?.maxHp
        // Preserve the existing partial projection when exact public units are unavailable.
        // Neither a range nor an absent stat is permission to consult the hidden actual maximum.
        if (actorHp == null || targetHp == null || actorHp.minimum != actorHp.maximum ||
            targetHp.minimum != targetHp.maximum) return damage * ratio
        val damageHp = damage * targetHp.minimum
        val wholeDamage = damageHp.roundToLong()
        val transferred = if (roundActualHit && wholeDamage.toDouble() / targetHp.minimum == damage) {
            (wholeDamage * ratio).roundToLong().coerceAtLeast(1).toDouble()
        } else {
            // A fractional expectation is not an actual hit and must not be rounded into one.
            damageHp * ratio
        }
        return transferred / actorHp.minimum
    }
}
