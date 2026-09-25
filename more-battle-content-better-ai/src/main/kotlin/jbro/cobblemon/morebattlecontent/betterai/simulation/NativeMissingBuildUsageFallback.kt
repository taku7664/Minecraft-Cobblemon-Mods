package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentBuildUsageEntry
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentSpreadUsage

/**
 * A deliberately labelled low-information prior for ordinary forms absent from the dated usage
 * snapshot. Cobblemon can field older or addon species that a Gen 9 tournament table never saw;
 * absence from that table is not evidence that the public preview species is impossible.
 *
 * Only public legal abilities, public base types, legal generic items and legal EV spreads enter
 * these worlds. Item-dependent or otherwise non-base forms remain fail-closed because this pool
 * does not describe their required item or locked Tera type.
 */
internal object NativeMissingBuildUsageFallback {
    fun forPokemon(pokemon: BattleOpponentTeamPreviewPokemonView): LocalOpponentBuildUsageEntry? {
        val pool = pokemon.buildCandidatePool ?: return null
        if (canonical(pokemon.formId.orEmpty()) !in BASE_FORMS) return null
        val abilities = pool.abilities.map { canonical(it.abilityId) }.filter(String::isNotBlank)
            .distinct().sorted()
        val types = pokemon.knownTypeIds.map(::canonical).filter(STANDARD_TYPES::contains)
            .distinct().sorted()
        if (abilities.isEmpty() || types.isEmpty()) return null
        return LocalOpponentBuildUsageEntry(
            abilityRates = abilities.associateWith { 1.0 / abilities.size },
            itemRates = GENERIC_ITEM_RATES,
            noItemRate = 0.05,
            spreads = GENERIC_SPREADS,
            unresolvedSpreadRate = 0.0,
            teraTypeRates = types.associateWith { 1.0 / types.size },
        )
    }

    private fun spread(nature: String, first: String, second: String): LocalOpponentSpreadUsage =
        LocalOpponentSpreadUsage(
            natureId = nature,
            evs = STAT_IDS.associateWith { stat ->
                when (stat) {
                    first, second -> 252
                    "hp" -> if (first == "hp" || second == "hp") 252 else 4
                    else -> 0
                }
            },
            rate = 1.0 / 6.0,
        )

    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private val BASE_FORMS = setOf("", "normal", "default", "base", "standard")
    private val STANDARD_TYPES = setOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )
    private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    private val GENERIC_ITEM_RATES = linkedMapOf(
        "leftovers" to 0.25,
        "lifeorb" to 0.20,
        "choiceband" to 0.15,
        "choicespecs" to 0.15,
        "choicescarf" to 0.10,
        "focussash" to 0.10,
    )
    private val GENERIC_SPREADS = listOf(
        spread("jolly", "atk", "spe"),
        spread("adamant", "hp", "atk"),
        spread("timid", "spa", "spe"),
        spread("modest", "hp", "spa"),
        spread("bold", "hp", "def"),
        spread("calm", "hp", "spd"),
    )
}
