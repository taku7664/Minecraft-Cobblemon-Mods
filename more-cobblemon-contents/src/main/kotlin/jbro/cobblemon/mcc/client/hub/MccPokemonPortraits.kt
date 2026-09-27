package jbro.cobblemon.mcc.client.hub

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import jbro.cobblemon.mcc.internal.compat.cobblemon173.findMccForm
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * Turns MCC's catalog and party identities into UI kit portrait contents. Only the catalog's form names are
 * resolved here; drawing the portrait is the UI kit's job.
 */
object MccPokemonPortraits {
    /** A catalog or opponent Pokemon; [stateKey] must stay the same for the same shown Pokemon. */
    fun pokemon(stateKey: String, speciesId: String, formId: String?, animate: Boolean = true): CobblemonUiRenderContent.Pokemon? {
        val species = ResourceLocation.tryParse(speciesId) ?: return null
        return CobblemonUiRenderContent.Pokemon(species, aspects(species, formId), stateKey, animate)
    }

    /** One of the viewer's own party Pokemon, drawn from the live party while it is still there. */
    fun party(pokemonId: UUID, speciesId: String, formId: String?, animate: Boolean = true): CobblemonUiRenderContent.PartyPokemon =
        CobblemonUiRenderContent.PartyPokemon(pokemonId, pokemon("party:$pokemonId", speciesId, formId, animate), animate)

    private fun aspects(speciesId: ResourceLocation, formId: String?): Set<String> {
        // Live Pokemon name their base form "Normal" while catalogs use lower case, so it is matched loosely.
        if (formId.isNullOrBlank() || formId.equals("normal", ignoreCase = true)) return emptySet()
        return try {
            PokemonSpecies.getByIdentifier(speciesId)?.findMccForm(formId)?.aspects?.toSet().orEmpty()
        } catch (_: RuntimeException) {
            emptySet()
        } catch (_: LinkageError) {
            emptySet()
        }
    }
}
