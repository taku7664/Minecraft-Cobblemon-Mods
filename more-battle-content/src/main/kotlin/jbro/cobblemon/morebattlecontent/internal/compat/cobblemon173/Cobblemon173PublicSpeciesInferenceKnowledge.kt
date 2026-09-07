package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.internal.ai.PublicAbilityPossibility
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesInferenceKnowledge
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMoveKnowledge
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMovePool
import com.cobblemon.mod.common.pokemon.FormData
import net.minecraft.resources.ResourceLocation

/** Resolves rule pools from the publicly presented species/form, never a live Pokemon's set. */
internal object Cobblemon173PublicSpeciesInferenceKnowledge : PublicSpeciesInferenceKnowledge, PublicSpeciesMoveKnowledge {
    override fun possibleAbilities(speciesId: String, formId: String?): List<PublicAbilityPossibility>? = runCatching {
        val form = publicForm(speciesId, formId) ?: return null
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

    override fun possibleMoves(speciesId: String, formId: String?): PublicSpeciesMovePool? = runCatching {
        val form = publicForm(speciesId, formId) ?: return null
        PublicSpeciesMovePool(
            form.moves.getAllLegalMoves().map { canonical(it.name) }.filter(String::isNotBlank).toSet(),
            "cobblemon:1.7.3/form_learnset/all_legal_moves",
        )
    }.getOrNull()

    private fun publicForm(speciesId: String, formId: String?): FormData? {
        val directSpecies = ResourceLocation.tryParse(speciesId)?.let(PokemonSpecies::getByIdentifier)
            ?: PokemonSpecies.getByName(speciesId.substringAfter(':'))
        return directSpecies?.let { species ->
            formId?.let(species::findMbcForm) ?: species.standardForm
        } ?: formByPublicShowdownId(speciesId)
    }

    private fun formByPublicShowdownId(speciesId: String) = PokemonSpecies.species.asSequence()
        .flatMap { species -> (species.forms + species.standardForm).distinct().asSequence() }
        .firstOrNull { form -> canonical(form.showdownId()) == canonical(speciesId) }

    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
