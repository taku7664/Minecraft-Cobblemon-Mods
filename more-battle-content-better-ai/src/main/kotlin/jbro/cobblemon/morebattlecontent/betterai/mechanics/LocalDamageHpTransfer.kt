package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import kotlin.math.roundToLong

/** Converts damage in the target's HP units to a drain/recoil change in the user's units. */
internal object LocalDamageHpTransfer {
    fun hasExactMaxHp(actor: BattlePokemonStateView?, target: BattlePokemonStateView?): Boolean {
        val actorHp = actor?.combatStats?.maxHp ?: return false
        val targetHp = target?.combatStats?.maxHp ?: return false
        return actorHp.minimum == actorHp.maximum && targetHp.minimum == targetHp.maximum
    }

    fun fraction(damage: Double, ratio: Double, actor: BattlePokemonStateView?, target: BattlePokemonStateView?, roundActualHit: Boolean = true): Double {
        if (damage <= 0.0 || ratio <= 0.0) return 0.0
        val actorHp = actor?.combatStats?.maxHp
        val targetHp = target?.combatStats?.maxHp
        // Missing units retain the old coarse estimate; callers must mark transfer uncertainty.
        if (actorHp == null || targetHp == null) return damage * ratio
        if (!hasExactMaxHp(actor, target)) {
            // Public-range midpoint estimate, not a probability-weighted or exact HP result.
            val actorMid = (actorHp.minimum.toDouble() + actorHp.maximum) / 2.0
            val targetMid = (targetHp.minimum.toDouble() + targetHp.maximum) / 2.0
            return damage * ratio * targetMid / actorMid
        }
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
