package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Which mid-battle public boards a native world can be rebuilt from ([NativeBattleSituation]).
 *
 * The rebuild installs HP, status, stat stages, items, Terastallization, PP, a choice lock and the field. An
 * active volatile effect (Substitute, Leech Seed, confusion), a move lock it implies (Taunt, Encore, recharge),
 * or an active slot still waiting for its replacement is not rebuilt, so such a board waits for a later turn.
 */
internal object NativeMidBattleStateRules {
    /** Why [state] cannot be rebuilt, or null when it can. */
    fun blocker(state: BattleStateView): String? {
        if (state.format != BattleFormat.SINGLE) return "DOUBLES"
        if (state.turn < 1) return "TURN"
        BattleSide.entries.forEach { side ->
            val actives = state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }
            if (actives.size != 1 || actives.single().activeSlot != 0) return "ACTIVE_NOT_READY:${side.name}"
            val active = actives.single()
            active.knownVolatileEffectIds.firstOrNull { !isBetterAiMarker(it) }
                ?.let { return "VOLATILE:" + PublicIds.canonical(it) }
            val constraints = active.actionConstraints
            if (constraints.taunted || constraints.mustRecharge || constraints.encoreMoveId != null) {
                return "MOVE_LOCK:${side.name}"
            }
        }
        return null
    }
}
