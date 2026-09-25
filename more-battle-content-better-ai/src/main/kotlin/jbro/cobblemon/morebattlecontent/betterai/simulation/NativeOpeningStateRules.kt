package jbro.cobblemon.morebattlecontent.betterai.simulation

import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Shared boundary for observations that can exist before the first native simulated turn. */
internal object NativeOpeningStateRules {
    fun acceptsObservations(state: BattleStateView): Boolean {
        val activePokemonIds = state.pokemon.asSequence()
            .filter { it.activeSlot != null && !it.fainted }
            .map(BattlePokemonStateView::battlePokemonId)
            .toSet()
        // Cobblemon can repeat the same public opening switch line before the first action.
        // Repetition does not make the current board noninitial; an inactive actor or any
        // action/HP event still does. All three opening compilers share this boundary.
        return state.observedEvents.all { event ->
            event.turn == 0 && event.kind in OPENING_EVENT_KINDS && when (event.kind) {
                BattleObservedEventKind.FIELD_EFFECT_CHANGED -> event.actorPokemonId == null
                else -> event.actorPokemonId in activePokemonIds
            }
        }
    }

    private val OPENING_EVENT_KINDS = setOf(
        BattleObservedEventKind.SWITCHED,
        BattleObservedEventKind.ABILITY_REVEALED,
        BattleObservedEventKind.HELD_ITEM_REVEALED,
        BattleObservedEventKind.FIELD_EFFECT_CHANGED,
    )
}
