package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Builds future-action templates from the same public state given to every Brain. */
internal object Cobblemon173PublicActionCatalog {
    fun from(state: BattleStateView): BattlePublicActionCatalogView = BattlePublicActionCatalogView(
        state.pokemon.filterNot { it.fainted }.mapNotNull { pokemon ->
            val knowledge = when (pokemon.side) {
                BattleSide.ALLY -> BattlePublicMoveKnowledge.EXACT_OWN
                BattleSide.OPPONENT -> BattlePublicMoveKnowledge.PUBLICLY_REVEALED
            }
            val moves = pokemon.knownMoveIds.sorted().mapNotNull { moveId ->
                Cobblemon173ActionCandidateAdapter.publicMoveDetails(moveId)?.let { details ->
                    BattlePublicMoveOptionView(moveId, details, knowledge)
                }
            }
            BattlePokemonActionCatalogView(
                battlePokemonId = pokemon.battlePokemonId,
                moves = moves,
                moveSetComplete = pokemon.side == BattleSide.ALLY || moves.size >= MAX_MOVE_SLOTS,
            ).takeIf { moves.isNotEmpty() }
        },
    )

    private const val MAX_MOVE_SLOTS = 4
}
