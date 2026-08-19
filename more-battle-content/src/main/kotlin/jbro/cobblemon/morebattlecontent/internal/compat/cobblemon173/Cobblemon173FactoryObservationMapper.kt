package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryOpponentObservation
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet

internal object Cobblemon173FactoryObservationMapper {
    fun map(
        rentalsByToken: Map<UUID, FactoryRentalSet>,
        battlePokemonIdsByToken: Map<UUID, UUID>,
        publicPokemon: List<BattlePokemonStateView>,
    ): Map<String, FactoryOpponentObservation> {
        require(rentalsByToken.keys == battlePokemonIdsByToken.keys) {
            "Factory rental tokens and battle Pokemon identities must match"
        }
        val publicById = publicPokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        return rentalsByToken.mapNotNull { (token, set) ->
            val public = publicById[battlePokemonIdsByToken.getValue(token)] ?: return@mapNotNull null
            val publicMoves = public.knownMoveIds.mapTo(HashSet(), ::showdownId)
            val revealedMoves = set.moveIds.filterTo(linkedSetOf()) { showdownId(it) in publicMoves }
            val revealedAbility = set.abilityId.takeIf { expected ->
                public.knownAbilityId?.let(::showdownId) == showdownId(expected)
            }
            val revealedItem = set.heldItemId?.takeIf { expected ->
                public.knownHeldItemId?.let(::showdownId) == showdownId(expected)
            }
            set.setId to FactoryOpponentObservation(
                speciesId = set.speciesId,
                revealedMoveIds = revealedMoves,
                revealedAbilityId = revealedAbility,
                revealedHeldItemId = revealedItem,
            )
        }.toMap(LinkedHashMap())
    }

    private fun showdownId(resourceId: String): String = resourceId
        .substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
