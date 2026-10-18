package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Which mid-battle public boards a native world can be rebuilt from ([NativeBattleSituation]).
 *
 * The rebuild installs HP, status, stat stages, items, Terastallization, PP, a choice lock, confusion and the field.
 * Any other active volatile effect (Substitute, Leech Seed), a move lock it implies (Taunt, Encore, recharge),
 * or an active slot still waiting for its replacement is not rebuilt, so such a board waits for a later turn.
 */
internal object NativeMidBattleStateRules {
    /** Why [state] cannot be rebuilt, or null when it can. */
    fun blocker(state: BattleStateView): String? {
        if (state.turn < 1) return "TURN"
        // One slot in singles, two in doubles, each filled while the side has a Pokemon left to fill it.
        val slots = if (state.format == BattleFormat.SINGLE) 1 else 2
        BattleSide.entries.forEach { side ->
            val actives = state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }
            // Unseen ones count: an empty slot with a Pokemon still to come is a replacement not yet made.
            val living = state.remainingPokemonBySide.getValue(side)
            if (actives.size != minOf(slots, living) || actives.any { it.activeSlot !in 0 until slots } ||
                actives.map { it.activeSlot }.distinct().size != actives.size) return "ACTIVE_NOT_READY:${side.name}"
            actives.forEach { active ->
                active.knownVolatileEffectIds.firstOrNull {
                    !isBetterAiMarker(it) && !isDynamaxMarker(it) && PublicIds.canonical(it) !in REBUILT_VOLATILES
                }
                    ?.let { return "VOLATILE:" + PublicIds.canonical(it) }
                val constraints = active.actionConstraints
                if (constraints.taunted || constraints.mustRecharge || constraints.encoreMoveId != null) {
                    return "MOVE_LOCK:${side.name}"
                }
            }
        }
        return null
    }

    private val REBUILT_VOLATILES = setOf("confusion", "dynamax")
    private fun isDynamaxMarker(id: String): Boolean = id.startsWith(LocalDynamax.TURNS) || id == LocalDynamax.GIGANTAMAX
}
