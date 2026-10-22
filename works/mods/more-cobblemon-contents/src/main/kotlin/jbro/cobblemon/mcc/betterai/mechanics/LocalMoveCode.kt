package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleShowdownMoveEffects
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What a move does as its code in the Showdown bundle shows it ([BattleDeclarativeMoveEffects]' flags), so the brain
 * names no move to find Rest, Sleep Talk, Yawn or Glaive Rush (user decision 2026-10-20: no hardcoding where the data
 * can tell). Read from the bundle by id, the same for a live candidate and for a recorded one.
 */
internal object LocalMoveCode {
    private fun flags(moveId: String?): Set<String> = BattleShowdownMoveEffects.flags(moveId?.let(PublicIds::canonical))

    private fun flagValue(moveId: String?, prefix: String): String? =
        flags(moveId).firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    /** The sleep counter the move sets on its user (Rest: 3), or null. */
    fun userSleepTurns(moveId: String?): Int? = flagValue(moveId, BattleDeclarativeMoveEffects.SLEEP_TURNS_FLAG)?.toIntOrNull()

    /** Usable asleep and calling one of the user's other moves (Sleep Talk). */
    fun callsOwnMoveAsleep(moveId: String?): Boolean {
        val id = moveId?.let(PublicIds::canonical) ?: return false
        return BattleDeclarativeMoveEffects.CALLS_OWN_MOVE_FLAG in flags(id) &&
            BattleShowdownMoveEffects.resolve(id)?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.USABLE_WHILE_ASLEEP }
    }

    /** The volatile the move puts on its target that ends in sleep (Yawn's `yawn`), or null. */
    fun delayedSleepVolatile(moveId: String?): String? = flagValue(moveId, BattleDeclarativeMoveEffects.DELAYED_SLEEP_FLAG)

    /** The volatile the move puts on its user under which every hit on it lands and doubles (Glaive Rush's), or null. */
    fun exposingVolatile(moveId: String?): String? = flagValue(moveId, BattleDeclarativeMoveEffects.EXPOSES_USER_FLAG)

    /** Every volatile any move uses to put its target to sleep later. */
    val delayedSleepVolatiles: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BattleShowdownMoveEffects.flagValues(BattleDeclarativeMoveEffects.DELAYED_SLEEP_FLAG)
    }

    /** Every volatile any move puts on its user that exposes it so. */
    val exposingVolatiles: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BattleShowdownMoveEffects.flagValues(BattleDeclarativeMoveEffects.EXPOSES_USER_FLAG)
    }

    /** Every volatile any move puts on its user that keeps it in against a forced switch (Ingrain's). */
    val anchoringVolatiles: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BattleShowdownMoveEffects.flagValues(BattleDeclarativeMoveEffects.ANCHORS_USER_FLAG)
    }

    fun setsTerrain(moveId: String?): Boolean = BattleDeclarativeMoveEffects.SETS_TERRAIN_FLAG in flags(moveId)

    val repeatFailureVolatiles: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BattleShowdownMoveEffects.flagValues(BattleDeclarativeMoveEffects.REPEAT_FAILS_WHILE_VOLATILE_FLAG)
    }

    /** The bypass list in Max Guard's onTryHit, read from the bundled source rather than named in the scorer. */
    val maxGuardBypasses: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val source = BattleShowdownMoveEffects.showdownSource("data/moves.js") ?: return@lazy emptySet()
        val properties = BattleDeclarativeMoveEffects.entries(source, "const Moves =")[maxProtectMoveId] ?: return@lazy emptySet()
        val condition = properties["condition"].orEmpty()
        val list = Regex("""bypassesMaxGuard\s*=\s*\[([\s\S]*?)]""").find(condition)?.groupValues?.get(1).orEmpty()
        Regex("""["']([a-z]+)["']""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    /** The Max Move a Dynamaxed Pokemon's status move turns into (Max Guard), or null when the bundle cannot be read. */
    val maxProtectMoveId: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BattleShowdownMoveEffects.idsWithFlag(BattleDeclarativeMoveEffects.MAX_PROTECT_FLAG).firstOrNull()
    }
}
