package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.Collections
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.*

/** Lazy moveset hypotheses. Alternatives are possibilities, not a calibrated usage distribution. */
internal object LocalOpponentMoveHypotheses {
    /** Templates retain source PP; the action factory deducts branch uses exactly once. */
    fun options(pokemon: BattlePokemonStateView, catalog: BattlePublicActionCatalogView,
                history: RecursiveActionHistory): Map<String, BattleMoveCandidateView> {
        if (pokemon.side != BattleSide.OPPONENT || pokemon.fainted) return emptyMap()
        if (catalog.isMoveSetComplete(pokemon.battlePokemonId)) return emptyMap()
        val pool = catalog.candidatePools.singleOrNull { it.battlePokemonId == pokemon.battlePokemonId }
            ?.takeIf { it.speciesId == pokemon.speciesId && it.formId == pokemon.formId } ?: return emptyMap()
        val known = pokemon.knownMoveIds.mapTo(hashSetOf(), ::canonical)
        val assumed = history.assumedOpponentMoveIds[pokemon.battlePokemonId].orEmpty()
        val occupied = known + assumed
        return pool.moveDetails.filter { (move, details) ->
            val key = canonical(move)
            val used = history.moveUses.entries.filter {
                it.key.pokemonId == pokemon.battlePokemonId && canonical(it.key.moveId) == key
            }.sumOf { it.value }
            key !in known && (key in assumed || occupied.size < 4) && details.currentPp > used
        }
    }

    /** Commit on selection, even if the projected move later fails or is interrupted. */
    fun assume(pokemon: BattlePokemonStateView, catalog: BattlePublicActionCatalogView,
               history: RecursiveActionHistory, moveId: String): RecursiveActionHistory {
        require(moveId in options(pokemon, catalog, history)) { "Move is not an available hypothesis" }
        val id = pokemon.battlePokemonId
        val moves = history.assumedOpponentMoveIds[id].orEmpty() + canonical(moveId)
        return history.copy(assumedOpponentMoveIds = Collections.unmodifiableMap(
            history.assumedOpponentMoveIds + (id to Collections.unmodifiableSet(moves))))
    }

    private fun canonical(value: String) = value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
