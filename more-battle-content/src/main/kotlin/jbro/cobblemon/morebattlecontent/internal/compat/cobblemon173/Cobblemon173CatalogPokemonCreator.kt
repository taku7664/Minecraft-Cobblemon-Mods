package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * Creates a catalog Pokemon while keeping Cobblemon's aspect-driven form state consistent.
 *
 * PokemonProperties applies `form` before initialization. Cobblemon then recalculates aspects during
 * initialization and selects the standard form again unless the selected form's aspects are retained.
 */
internal object Cobblemon173CatalogPokemonCreator {
    fun create(properties: PokemonProperties, catalogFormId: String?): Pokemon {
        return preserve(properties.create(), catalogFormId)
    }

    internal fun preserve(pokemon: Pokemon, catalogFormId: String?): Pokemon {
        if (catalogFormId == null) return pokemon

        val targetForm = pokemon.species.findMbcForm(catalogFormId) ?: throw IllegalArgumentException(
            "Unknown form '$catalogFormId' for catalog species ${pokemon.species.resourceIdentifier}",
        )

        if (targetForm == pokemon.species.standardForm) return pokemon
        require(targetForm.aspects.isNotEmpty()) {
            "Alternate form '${targetForm.name}' for ${pokemon.species.resourceIdentifier} has no persistent aspects"
        }

        pokemon.forcedAspects = pokemon.forcedAspects + targetForm.aspects
        check(pokemon.form == targetForm) {
            "Failed to preserve form '${targetForm.name}' for ${pokemon.species.resourceIdentifier}; " +
                "resolved '${pokemon.form.name}' from aspects ${pokemon.aspects}"
        }
        return pokemon
    }
}
