package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import com.cobblemon.mod.common.pokemon.Species
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewAbilityView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewBuildPoolView
import jbro.cobblemon.morebattlecontent.internal.ai.PublicAbilityPossibility
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesInferenceKnowledge
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMoveKnowledge
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMovePool
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import net.minecraft.resources.ResourceLocation

/** Resolves rule pools from the publicly presented species/form, never a live Pokemon's set. */
internal object Cobblemon173PublicSpeciesInferenceKnowledge : PublicSpeciesInferenceKnowledge, PublicSpeciesMoveKnowledge {
    override fun possibleAbilities(speciesId: String, formId: String?): List<PublicAbilityPossibility>? = compatibilityCallOrNull {
        val form = publicSpeciesAndForm(speciesId, formId)?.second ?: return null
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
    }

    override fun possibleMoves(speciesId: String, formId: String?): PublicSpeciesMovePool? = compatibilityCallOrNull {
        val form = publicSpeciesAndForm(speciesId, formId)?.second ?: return null
        PublicSpeciesMovePool(
            form.moves.getAllLegalMoves().map { canonical(it.name) }.filter(String::isNotBlank).toSet(),
            "cobblemon:1.7.3/form_learnset/all_legal_moves",
        )
    }

    fun publicPreviewFacts(
        speciesId: String,
        formId: String?,
        level: Int?,
    ): Cobblemon173PublicPreviewFacts? = compatibilityCallOrNull {
        val (species, form) = publicSpeciesAndForm(speciesId, formId) ?: return null
        Cobblemon173PublicPreviewFacts(
            knownTypeIds = form.types.mapTo(linkedSetOf()) { it.name },
            combatStats = level?.let { Cobblemon173PublicStatHypothesis.fromForm(it, form) },
            knownFormStates = level?.let { Cobblemon173KnownFormStates.publicRanges(it, species) }.orEmpty(),
            showdownSpeciesId = form.showdownId(),
            buildCandidatePool = publicBuildPool(speciesId, formId, form),
        )
    }

    private fun publicBuildPool(
        speciesId: String,
        formId: String?,
        form: FormData,
    ): BattleOpponentPreviewBuildPoolView? {
        val abilities = form.abilities.mapNotNull { potential ->
            canonical(potential.template.name).takeIf(String::isNotBlank)?.let { abilityId ->
                BattleOpponentPreviewAbilityView(
                    abilityId,
                    if (potential is HiddenAbility) BattleAbilityAvailability.HIDDEN else BattleAbilityAvailability.REGULAR,
                )
            }
        }.distinctBy(BattleOpponentPreviewAbilityView::abilityId)
        val maleRatio = form.maleRatio.toDouble()
        val genderRates = when {
            maleRatio == -1.0 -> mapOf("N" to 1.0)
            maleRatio == 0.0 -> mapOf("F" to 1.0)
            maleRatio == 1.0 -> mapOf("M" to 1.0)
            maleRatio > 0.0 && maleRatio < 1.0 -> linkedMapOf("M" to maleRatio, "F" to 1.0 - maleRatio)
            else -> return null
        }
        return abilities.takeIf(List<*>::isNotEmpty)?.let {
            BattleOpponentPreviewBuildPoolView(
                speciesId = speciesId,
                formId = formId,
                abilities = abilities,
                genderRates = genderRates,
                sourceId = "cobblemon:1.7.3/form_abilities_and_gender_ratio",
                baseStats = mapOf(
                    "hp" to requireNotNull(form.baseStats[Stats.HP]),
                    "atk" to requireNotNull(form.baseStats[Stats.ATTACK]),
                    "def" to requireNotNull(form.baseStats[Stats.DEFENCE]),
                    "spa" to requireNotNull(form.baseStats[Stats.SPECIAL_ATTACK]),
                    "spd" to requireNotNull(form.baseStats[Stats.SPECIAL_DEFENCE]),
                    "spe" to requireNotNull(form.baseStats[Stats.SPEED]),
                ),
            )
        }
    }

    private fun publicSpeciesAndForm(speciesId: String, formId: String?): Pair<Species, FormData>? {
        val directSpecies = ResourceLocation.tryParse(speciesId)?.let(PokemonSpecies::getByIdentifier)
            ?: PokemonSpecies.getByName(speciesId.substringAfter(':'))
        return directSpecies?.let { species ->
            val form = if (formId == null) species.standardForm else species.findMbcForm(formId) ?: return null
            species to form
        } ?: speciesAndFormByPublicShowdownId(speciesId)
    }

    private fun speciesAndFormByPublicShowdownId(speciesId: String) = PokemonSpecies.species.asSequence()
        .mapNotNull { species ->
            (species.forms + species.standardForm).distinct()
                .firstOrNull { form -> canonical(form.showdownId()) == canonical(speciesId) }
                ?.let { form -> species to form }
        }
        .firstOrNull()

    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
