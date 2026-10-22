package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Formes a Pokemon takes on during battle (Mimikyu's busted disguise, Wishiwashi's school). The team preview
 * names the forme it started in, so a revealed Pokemon is matched against the preview by its starting forme.
 */
internal object NativeInBattleFormes {
    fun startingSpecies(speciesId: String): String {
        val id = PublicIds.canonical(speciesId)
        return IN_BATTLE[id] ?: megaBase(id) ?: id
    }

    /**
     * Whether the revealed [pokemon] is the team preview's [preview]. A Showdown switch line names the species as
     * `showdown:<id>` with the form folded in (Zoroark-Hisui), while the Cobblemon preview names `cobblemon:<species>`
     * plus a separate form, so the ids are compared canonical, namespace dropped, from the starting forme.
     */
    fun samePublicIdentity(pokemon: BattlePokemonStateView, preview: BattleOpponentTeamPreviewPokemonView): Boolean {
        val publicForm = pokemon.formId
        val previewForm = preview.formId
        val original = PublicIds.canonical(pokemon.speciesId)
        val separateMega = publicForm?.let { megaBase(original + PublicIds.canonical(it)) }
        val species = separateMega ?: startingSpecies(pokemon.speciesId)
        val transformed = species != original || separateMega != null
        val baseIdentity = species == PublicIds.canonical(preview.speciesId) &&
            (transformed || publicForm == null || previewForm == null || PublicIds.canonical(publicForm) == PublicIds.canonical(previewForm))
        val showdownIdentity = pokemon.speciesId.startsWith("showdown:") &&
            preview.showdownSpeciesId?.let { species == PublicIds.canonical(it) } == true
        return baseIdentity || showdownIdentity
    }

    /** The in-battle forme [speciesId] names, or null when it is a starting forme. */
    fun inBattleForme(speciesId: String): String? = PublicIds.canonical(speciesId).takeIf { it in IN_BATTLE || megaBase(it) != null }

    /** Read the registered form's starting species; a regional form is never collapsed to its family. */
    private fun megaBase(id: String): String? {
        if ("mega" !in id) return null
        val species = EngineRuntimeDex.current().second.species(id) ?: return null
        if (!species.isMega && !species.forme.startsWith("Mega")) return null
        return PublicIds.canonical(species.changesFrom ?: species.baseSpecies)
    }

    private val IN_BATTLE = mapOf(
        "mimikyubusted" to "mimikyu",
        "wishiwashischool" to "wishiwashi",
        "miniormeteor" to "minior",
        "darmanitanzen" to "darmanitan",
        "darmanitangalarzen" to "darmanitangalar",
        "aegislashblade" to "aegislash",
        "cherrimsunshine" to "cherrim",
        "meloettapirouette" to "meloetta",
        "morpekohangry" to "morpeko",
        "cramorantgulping" to "cramorant",
        "cramorantgorging" to "cramorant",
        "eiscuenoice" to "eiscue",
        "palafinhero" to "palafin",
        "zygardecomplete" to "zygarde",
        "terapagosterastal" to "terapagos",
        "terapagosstellar" to "terapagos",
    )
}
