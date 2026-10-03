package jbro.cobblemon.mcc.internal.ai

/** The live candidate policy reserves explicit friendly attacks for supported beneficial effects. */
internal object BattleProductAllyTargetPolicy {
    fun permits(
        moveId: String,
        targetSide: BattleSide?,
        ordinarilyFoeAimed: Boolean,
        damageCategory: BattleMoveDamageCategory?,
    ): Boolean = targetSide != BattleSide.ALLY || !ordinarilyFoeAimed ||
        damageCategory == BattleMoveDamageCategory.STATUS || PublicIds.canonical(moveId) == "pollenpuff"
}
