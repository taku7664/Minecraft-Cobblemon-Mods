package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Builds future-action templates from the same public state given to every Brain. */
internal object Cobblemon173PublicActionCatalog {
    fun from(
        state: BattleStateView,
        ppSpent: Map<UUID, Map<String, Int>>,
        ownCurrentPp: Map<UUID, Map<String, Int>>,
        transformedPokemon: Set<UUID> = emptySet(),
        originalMoveIds: Map<UUID, Set<String>> = emptyMap(),
        originalPpSpent: Map<UUID, Map<String, Int>> = emptyMap(),
        moveDetails: (String) -> BattleMoveCandidateView? = Cobblemon173ActionCandidateAdapter::publicMoveDetails,
    ): BattlePublicActionCatalogView {
        fun entry(pokemon: jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView, original: Boolean): BattlePokemonActionCatalogView {
            val knowledge = when (pokemon.side) {
                BattleSide.ALLY -> BattlePublicMoveKnowledge.EXACT_OWN
                BattleSide.OPPONENT -> BattlePublicMoveKnowledge.PUBLICLY_REVEALED
            }
            val ids = if (original) originalMoveIds[pokemon.battlePokemonId].orEmpty() else pokemon.knownMoveIds
            val spent = if (original) originalPpSpent else ppSpent
            val moves = ids.sorted().mapNotNull { moveId ->
                moveDetails(moveId)?.let { details ->
                    val actual = if (!original && pokemon.side == BattleSide.ALLY) {
                        ownCurrentPp[pokemon.battlePokemonId]?.get(moveId)
                    } else null
                    BattlePublicMoveOptionView(moveId, details.copy(currentPp = remainingPp(
                        ppCapacity(details.currentPp, !original && pokemon.battlePokemonId in transformedPokemon),
                        spent[pokemon.battlePokemonId]?.get(moveId) ?: 0,
                        actual,
                    )), knowledge)
                }
            }
            return BattlePokemonActionCatalogView(
                battlePokemonId = pokemon.battlePokemonId,
                moves = moves,
                moveSetComplete = pokemon.side == BattleSide.ALLY || moves.size >= MAX_MOVE_SLOTS,
            )
        }
        val living = state.pokemon.filterNot { it.fainted }
        return BattlePublicActionCatalogView(
            living.map { entry(it, false) }.filter { it.moves.isNotEmpty() },
            living.filter { it.battlePokemonId in transformedPokemon && it.battlePokemonId in originalMoveIds }
                .map { entry(it, true) },
        )
    }

    /** Native Transform has a temporary five-PP pool, except for one-PP moves. */
    internal fun ppCapacity(maximumPp: Int, transformed: Boolean): Int =
        if (!transformed) maximumPp else if (maximumPp == 1) 1 else 5

    /** A point estimate under the PP Max assumption, not knowledge of the opponent's real PP. */
    internal fun remainingPp(maximumPp: Int, ppSpent: Int, actualCurrentPp: Int?): Int {
        require(maximumPp >= 0 && ppSpent >= 0)
        require(actualCurrentPp == null || actualCurrentPp >= 0)
        return actualCurrentPp ?: (maximumPp - ppSpent).coerceAtLeast(0)
    }

    private const val MAX_MOVE_SLOTS = 4
}
