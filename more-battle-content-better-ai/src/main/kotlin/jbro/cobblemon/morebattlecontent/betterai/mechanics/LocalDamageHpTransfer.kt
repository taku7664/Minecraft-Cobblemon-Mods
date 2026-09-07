package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import kotlin.math.roundToLong

/** Converts damage in the target's HP units to a drain/recoil change in the user's units. */
internal object LocalDamageHpTransfer {
    /** Conservative on-hit bounds, including integer rounding, not a probability interval. */
    fun bounds(damage: BattleFractionRange, ratio: BattleFractionRange,
               actor: BattlePokemonStateView?, target: BattlePokemonStateView?, hpLimit: Double): BattleFractionRange {
        val limit = hpLimit.coerceIn(0.0, 1.0)
        if (damage.maximum <= 0.0 || ratio.maximum <= 0.0 || limit == 0.0) return BattleFractionRange(0.0, 0.0)
        val actorHp = actor?.combatStats?.maxHp ?: return BattleFractionRange(0.0, limit)
        val targetHp = target?.combatStats?.maxHp ?: return BattleFractionRange(0.0, limit)
        if (hasExactMaxHp(actor, target) && damage.minimum == damage.maximum && ratio.minimum == ratio.maximum) {
            val value = fraction(damage.minimum, ratio.minimum, actor, target).coerceIn(0.0, limit)
            return BattleFractionRange(value, value)
        }
        val minimum = ((damage.minimum * targetHp.minimum * ratio.minimum - 0.5).coerceAtLeast(0.0) / actorHp.maximum).coerceAtMost(limit)
        val maximum = ((damage.maximum * targetHp.maximum * ratio.maximum + 0.5).coerceAtLeast(1.0) / actorHp.minimum).coerceAtMost(limit)
        return BattleFractionRange(minimum, maximum)
    }

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
