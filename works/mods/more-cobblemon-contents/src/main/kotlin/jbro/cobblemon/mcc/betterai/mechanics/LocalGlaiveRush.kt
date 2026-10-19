package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Whether [pokemon] is still open from its Glaive Rush: hits on it land for double and never miss until its next move.
 *
 * Showdown announces the state only silently, so the public record reads it from the move: its last move used was Glaive
 * Rush and it has not moved again nor left the field since. Read from the volatile alone, which the game never sets, a +1
 * Baxcalibur that had just Glaive Rushed stayed in front of a Scizor whose Bullet Punch then knocked it out (hand-played
 * Candice, 2026-10-20).
 */
internal object LocalGlaiveRush {
    fun vulnerable(state: BattleStateView?, pokemon: BattlePokemonStateView?): Boolean {
        pokemon ?: return false
        // The volatile and the move that sets it, both read from the move's code (no move named, 2026-10-20).
        val exposing = LocalMoveCode.exposingVolatiles
        if (pokemon.knownVolatileEffectIds.any { PublicIds.canonical(it) in exposing }) return true
        val events = state?.observedEvents ?: return false
        val last = events.lastOrNull { it.actorPokemonId == pokemon.battlePokemonId &&
            (it.kind == BattleObservedEventKind.MOVE_USED || it.kind == BattleObservedEventKind.SWITCHED) } ?: return false
        return last.kind == BattleObservedEventKind.MOVE_USED && LocalMoveCode.exposingVolatile(last.publicValueId) != null
    }}
