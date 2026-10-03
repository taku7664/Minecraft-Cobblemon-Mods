package jbro.cobblemon.mcc.internal.ai

/** Keeps the live adapter's existing friendly-target policy shared by both request generators. */
internal object BattleProductAllyTargetPolicy {
    fun permits(
        moveId: String,
        targetSide: BattleSide?,
        ordinarilyFoeAimed: Boolean,
        damageCategory: BattleMoveDamageCategory?,
    ): Boolean = targetSide != BattleSide.ALLY || !ordinarilyFoeAimed ||
        damageCategory == BattleMoveDamageCategory.STATUS || PublicIds.canonical(moveId) == "pollenpuff"
}
