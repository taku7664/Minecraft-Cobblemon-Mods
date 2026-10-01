package jbro.cobblemon.ui.extended.battle.state

import jbro.cobblemon.ui.extended.CobblemonUi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tracks only moves that the opponent has publicly revealed. */
object MoveTracker {

    private val revealedMoves = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun clear() {
        revealedMoves.clear()
    }

    fun addRevealedMove(pokemonName: String, moveId: String, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonUi.LOGGER.debug("MoveTracker: Unknown Pokemon '$pokemonName' for move tracking")
            return
        }
        val moves = revealedMoves.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
        val normalizedId = moveId
            .substringAfterLast(':')
            .substringAfterLast('.')
            .lowercase()
            .filter(Char::isLetterOrDigit)
        if (moves.add(normalizedId)) {
            CobblemonUi.LOGGER.debug("MoveTracker: $pokemonName revealed move '$normalizedId'")
        }

    }

    fun getRevealedMoves(uuid: UUID): Set<String> = revealedMoves[uuid]?.toSet() ?: emptySet()
}
