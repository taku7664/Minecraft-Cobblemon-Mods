package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState

/** Supplies one branch's move pool consistently to projection, leaf evaluation and memoization. */
internal object LocalBranchMoveInputs {
    /** Temporary mid-turn view; the end-turn projector still receives the original history. */
    fun afterExecutedMoves(history: RecursiveActionHistory, executed: Map<UUID, String>): RecursiveActionHistory {
        if (executed.isEmpty()) return history
        val uses = history.moveUses.toMutableMap()
        executed.forEach { (pokemonId, moveId) ->
            if (history.chargingMoveByPokemon[pokemonId] == moveId) return@forEach
            val key = RecursiveMoveUseKey(pokemonId, moveId)
            uses[key] = (uses[key] ?: 0) + 1
        }
        return history.copy(moveUses = uses)
    }

    fun key(fingerprint: String, history: RecursiveActionHistory) = LocalBranchMoveInputKey(
        fingerprint, history.restoredOriginalPokemonIds.toSet(), history.moveUses.toMap())

    fun state(state: BattleStateView, catalog: BattlePublicActionCatalogView, history: RecursiveActionHistory): BattleStateView {
        if (history.restoredOriginalPokemonIds.isEmpty()) return state
        val originals = catalog.originalEntries.filter { it.battlePokemonId in history.restoredOriginalPokemonIds }
            .associate { it.battlePokemonId to it.moves.mapTo(linkedSetOf()) { move -> move.moveId } }
        var changed = false
        val pokemon = state.pokemon.map { pokemon ->
            val moves = originals[pokemon.battlePokemonId]
            if (moves == null || moves == pokemon.knownMoveIds) pokemon else {
                changed = true
                pokemon.copyState(knownMoveIds = moves)
            }
        }
        return if (changed) state.copyState(pokemon = pokemon) else state
    }

    fun context(source: BattleDecisionContext, state: BattleStateView, history: RecursiveActionHistory,
                spendPp: Boolean = false): BattleDecisionContext {
        val restored = source.publicActionCatalog.afterSwitch(history.restoredOriginalPokemonIds)
        // Projectors already consume history. History-free evaluators instead need remaining PP here.
        val catalog = if (!spendPp || history.moveUses.isEmpty()) restored else BattlePublicActionCatalogView(
            restored.entries.map { entry -> BattlePokemonActionCatalogView(entry.battlePokemonId,
                entry.moves.map { move ->
                    val used = history.moveUses[RecursiveMoveUseKey(entry.battlePokemonId, move.moveId)] ?: 0
                    move.copy(details = move.details.copy(currentPp = (move.details.currentPp - used).coerceAtLeast(0)))
                }, entry.moveSetComplete)
            }, restored.originalEntries)
        return BattleDecisionContext(source.requestId, this.state(state, source.publicActionCatalog, history),
            source.candidates, source.deadlineEpochMillis, source.memory, catalog)
    }
}

internal data class LocalBranchMoveInputKey(val fingerprint: String, val restored: Set<UUID>,
                                          val moveUses: Map<RecursiveMoveUseKey, Int>)
