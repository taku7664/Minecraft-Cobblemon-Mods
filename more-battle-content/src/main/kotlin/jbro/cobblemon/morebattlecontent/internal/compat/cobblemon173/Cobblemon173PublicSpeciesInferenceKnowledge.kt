package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.internal.ai.PublicAbilityPossibility
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesInferenceKnowledge
import net.minecraft.resources.ResourceLocation

/** Resolves only the ability pool declared by the publicly presented species/form. */
internal object Cobblemon173PublicSpeciesInferenceKnowledge : PublicSpeciesInferenceKnowledge {
    override fun possibleAbilities(speciesId: String, formId: String?): List<PublicAbilityPossibility>? = runCatching {
        val directSpecies = ResourceLocation.tryParse(speciesId)?.let(PokemonSpecies::getByIdentifier)
            ?: PokemonSpecies.getByName(speciesId.substringAfter(':'))
        val form = directSpecies?.let { species ->
            formId?.let(species::getFormByName) ?: species.standardForm
        } ?: formByPublicShowdownId(speciesId) ?: return null
        form.abilities.mapNotNull { potential ->
            val abilityId = potential.template.name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
            abilityId.takeIf(String::isNotBlank)?.let {
                PublicAbilityPossibility(
                    abilityId = it,
                    availability = if (potential is HiddenAbility) {
                        BattleAbilityAvailability.HIDDEN
                    } else {
                        BattleAbilityAvailability.REGULAR
                    },
                )
            }
        }.distinct()
    }.getOrNull()

    private fun formByPublicShowdownId(speciesId: String) = PokemonSpecies.species.asSequence()
        .flatMap { species -> (species.forms + species.standardForm).distinct().asSequence() }
        .firstOrNull { form -> canonical(form.showdownId()) == canonical(speciesId) }

    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
