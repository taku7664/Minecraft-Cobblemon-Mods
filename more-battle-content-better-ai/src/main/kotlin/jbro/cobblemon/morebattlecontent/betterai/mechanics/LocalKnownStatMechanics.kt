package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView

/** Applies deterministic power and stat modifiers whose item or ability is public. */
internal object LocalKnownStatMechanics {
    fun effectivePower(basePower: Int, actor: BattlePokemonStateView): Int {
        if (canonical(actor.knownAbilityId) == "technician" && basePower <= 60) {
            return (basePower * 1.5).toInt().coerceAtLeast(1)
        }
        return basePower
    }

    fun defence(
        value: BattleIntegerRange,
        category: BattleMoveDamageCategory,
        target: BattlePokemonStateView,
    ): BattleIntegerRange {
        val multiplier = if (
            category == BattleMoveDamageCategory.SPECIAL && canonical(target.knownHeldItemId) == "assaultvest"
        ) {
            1.5
        } else {
            1.0
        }
        return scale(value, multiplier)
    }

    private fun scale(value: BattleIntegerRange, multiplier: Double) = BattleIntegerRange(
        minimum = (value.minimum * multiplier).toInt().coerceAtLeast(1),
        maximum = (value.maximum * multiplier).toInt().coerceAtLeast(1),
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
}
