package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

/** Reconstructs and advances the public badly-poisoned damage counter. */
internal object LocalBadPoisonCounter {
    fun seed(state: BattleStateView): Map<UUID, Int> = state.pokemon.asSequence()
        .filter(::isActiveBadlyPoisoned)
        .associate { pokemon -> pokemon.battlePokemonId to observedCompletedTicks(state, pokemon.battlePokemonId) }

    fun advance(
        stateBefore: BattleStateView,
        stateAfterActions: BattleStateView,
        history: RecursiveActionHistory,
    ): Map<UUID, Int> = stateAfterActions.pokemon.asSequence()
        .filter(::isActiveBadlyPoisoned)
        .associate { pokemon ->
            val stayedIn = stateBefore.pokemon.any { before ->
                before.battlePokemonId == pokemon.battlePokemonId && isActiveBadlyPoisoned(before)
            }
            val completed = if (stayedIn) {
                (history.badPoisonTurnsByPokemon[pokemon.battlePokemonId] ?: 0) + 1
            } else {
                1
            }
            pokemon.battlePokemonId to completed.coerceAtMost(MAXIMUM_BAD_POISON_TURN)
        }

    private fun observedCompletedTicks(state: BattleStateView, pokemonId: UUID): Int {
        val statusTurn = state.observedEvents.asSequence()
            .filter { event ->
                event.kind == BattleObservedEventKind.STATUS_CHANGED &&
                    event.actorPokemonId == pokemonId &&
                    canonical(event.publicValueId) in BAD_POISON_IDS
            }
            .maxOfOrNull(BattleObservedEventView::turn)
        val switchTurn = state.observedEvents.asSequence()
            .filter { event -> event.kind == BattleObservedEventKind.SWITCHED && event.actorPokemonId == pokemonId }
            .maxOfOrNull(BattleObservedEventView::turn)
        val resetTurn = listOfNotNull(statusTurn, switchTurn).maxOrNull() ?: return 0
        return (state.turn - resetTurn).coerceIn(0, MAXIMUM_BAD_POISON_TURN)
    }

    private fun isActiveBadlyPoisoned(pokemon: BattlePokemonStateView): Boolean =
        pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0 &&
            canonical(pokemon.statusId) in BAD_POISON_IDS

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private val BAD_POISON_IDS = setOf("tox", "toxic", "badlypoisoned")
    const val MAXIMUM_BAD_POISON_TURN = 15
}
