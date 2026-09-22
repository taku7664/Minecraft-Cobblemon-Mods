package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** One-turn team guards that block only the move classes declared by Showdown. */
internal object LocalSideGuardRules {
    fun blocks(
        state: BattleStateView,
        attackingSide: BattleSide,
        action: BattleActionCandidate,
        protectedSide: BattleSide,
    ): Boolean {
        val details = action.moveDetails ?: return false
        val guards = state.field.sideConditions.getValue(protectedSide)
            .mapTo(linkedSetOf()) { LocalSideConditionRules.canonical(it.effectId) }
        if (
            CRAFTY_SHIELD in guards &&
            details.damageCategory == BattleMoveDamageCategory.STATUS &&
            details.targetPattern !in CRAFTY_SHIELD_EXEMPT_PATTERNS
        ) return true
        val effects = details.effects ?: return false
        if (PROTECT_FLAG !in effects.mechanicFlags) return false
        if (effects.effects.any { it.kind == BattleMoveEffectKind.BREAKS_PROTECTION }) return false
        return (WIDE_GUARD in guards && details.targetPattern in SPREAD_PATTERNS) ||
            (QUICK_GUARD in guards && LocalPublicTurnOrder.effectivePriority(state, attackingSide, action) > 0) ||
            (MAT_BLOCK in guards && details.damageCategory != BattleMoveDamageCategory.STATUS &&
                details.targetPattern != BattleMoveTargetPattern.SELF)
    }

    private const val PROTECT_FLAG = "protect"
    private const val CRAFTY_SHIELD = "craftyshield"
    private const val MAT_BLOCK = "matblock"
    private const val QUICK_GUARD = "quickguard"
    private const val WIDE_GUARD = "wideguard"
    private val SPREAD_PATTERNS = setOf(
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_OPPONENTS,
    )
    private val CRAFTY_SHIELD_EXEMPT_PATTERNS = setOf(
        BattleMoveTargetPattern.SELF,
        BattleMoveTargetPattern.ALL_ACTIVE,
    )
}
