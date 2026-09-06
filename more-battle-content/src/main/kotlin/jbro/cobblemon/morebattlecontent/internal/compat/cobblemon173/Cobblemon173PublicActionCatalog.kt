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
        moveUses: Map<UUID, Map<String, Int>>,
        ownCurrentPp: Map<UUID, Map<String, Int>>,
        moveDetails: (String) -> BattleMoveCandidateView? = Cobblemon173ActionCandidateAdapter::publicMoveDetails,
    ): BattlePublicActionCatalogView = BattlePublicActionCatalogView(
        state.pokemon.filterNot { it.fainted }.mapNotNull { pokemon ->
            val knowledge = when (pokemon.side) {
                BattleSide.ALLY -> BattlePublicMoveKnowledge.EXACT_OWN
                BattleSide.OPPONENT -> BattlePublicMoveKnowledge.PUBLICLY_REVEALED
            }
            val moves = pokemon.knownMoveIds.sorted().mapNotNull { moveId ->
                moveDetails(moveId)?.let { details ->
                    val actual = if (pokemon.side == BattleSide.ALLY) {
                        ownCurrentPp[pokemon.battlePokemonId]?.get(moveId)
                    } else null
                    BattlePublicMoveOptionView(moveId, details.copy(currentPp = remainingPp(
                        details.currentPp,
                        moveUses[pokemon.battlePokemonId]?.get(moveId) ?: 0,
                        actual,
                    )), knowledge)
                }
            }
            BattlePokemonActionCatalogView(
                battlePokemonId = pokemon.battlePokemonId,
                moves = moves,
                moveSetComplete = pokemon.side == BattleSide.ALLY || moves.size >= MAX_MOVE_SLOTS,
            ).takeIf { moves.isNotEmpty() }
        },
    )

    /** A point estimate under the PP Max assumption, not knowledge of the opponent's real PP. */
    internal fun remainingPp(maximumPp: Int, observedUses: Int, actualCurrentPp: Int?): Int {
        require(maximumPp >= 0 && observedUses >= 0)
        require(actualCurrentPp == null || actualCurrentPp >= 0)
        return actualCurrentPp ?: (maximumPp - observedUses).coerceAtLeast(0)
    }

    private const val MAX_MOVE_SLOTS = 4
}
