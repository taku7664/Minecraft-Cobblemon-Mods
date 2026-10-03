package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleProductAllyTargetPolicy

/** Applies the live friendly-target policy to complete native choices at every own search depth. */
internal object NativeProductAllyTargetPolicy {
    fun permits(
        action: BattleActionCandidate,
        fallbackCategory: (BattleActionCandidate) -> BattleMoveDamageCategory? = { it.moveDetails?.damageCategory },
    ): Boolean = action.componentActions.ifEmpty { listOf(action) }.all { part ->
        val category = when {
            "native_category_status" in part.tags -> BattleMoveDamageCategory.STATUS
            "native_category_physical" in part.tags -> BattleMoveDamageCategory.PHYSICAL
            "native_category_special" in part.tags -> BattleMoveDamageCategory.SPECIAL
            else -> fallbackCategory(part)
        }
        BattleProductAllyTargetPolicy.permits(part.moveId.orEmpty(), part.targets.singleOrNull()?.side,
            "native_target_normal" in part.tags || "native_target_any" in part.tags, category)
    }
}
