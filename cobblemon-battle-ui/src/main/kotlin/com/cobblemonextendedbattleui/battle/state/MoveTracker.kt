package jbro.cobblemon.battleui.extended.battle.state

import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tracks only moves that the opponent has publicly revealed. */
object MoveTracker {

    private val revealedMoves = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun clear() {
        revealedMoves.clear()
    }

    fun addRevealedMove(pokemonName: String, moveName: String, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("MoveTracker: Unknown Pokemon '$pokemonName' for move tracking")
            return
        }
        val moves = revealedMoves.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
        if (moves.add(moveName)) {
            CobblemonExtendedBattleUI.LOGGER.debug("MoveTracker: $pokemonName revealed move '$moveName'")
        }

    }

    fun getRevealedMoves(uuid: UUID): Set<String> = revealedMoves[uuid]?.toSet() ?: emptySet()
}
