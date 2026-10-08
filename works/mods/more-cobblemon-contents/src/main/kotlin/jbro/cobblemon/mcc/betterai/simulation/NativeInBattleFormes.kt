package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Formes a Pokemon takes on during battle (Mimikyu's busted disguise, Wishiwashi's school). The team preview
 * names the forme it started in, so a revealed Pokemon is matched against the preview by its starting forme.
 */
internal object NativeInBattleFormes {
    fun startingSpecies(speciesId: String): String {
        val id = PublicIds.canonical(speciesId)
        return IN_BATTLE[id] ?: id
    }

    /** The in-battle forme [speciesId] names, or null when it is a starting forme. */
    fun inBattleForme(speciesId: String): String? = PublicIds.canonical(speciesId).takeIf { it in IN_BATTLE }

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
