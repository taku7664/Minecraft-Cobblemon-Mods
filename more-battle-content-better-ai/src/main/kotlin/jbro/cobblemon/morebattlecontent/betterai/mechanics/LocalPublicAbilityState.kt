package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Whether a publicly known ability is active after deterministic suppression rules. */
internal object LocalPublicAbilityState {
    fun effectiveKnownAbility(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        val ability = canonical(pokemon?.knownAbilityId)
        return ability?.takeIf { pokemon != null && isActive(state, pokemon, it) }
    }

    fun isActive(state: BattleStateView, pokemon: BattlePokemonStateView, abilityId: String): Boolean {
        val ability = canonical(abilityId) ?: return false
        if (ability in CANNOT_SUPPRESS) return true
        val volatiles = pokemon.knownVolatileEffectIds.mapTo(hashSetOf()) { canonical(it).orEmpty() }
        if (GASTRO_ACID in volatiles) return false
        val itemProtected = !LocalPublicFieldMechanics.magicRoomActive(state) &&
            canonical(pokemon.knownHeldItemId) == ABILITY_SHIELD
        if (itemProtected || ability == NEUTRALIZING_GAS) return true
        return state.pokemon.none { active ->
            active.battlePokemonId != pokemon.battlePokemonId && active.activeSlot != null &&
                !active.fainted && active.hpFraction > 0.0 &&
                canonical(active.knownAbilityId) == NEUTRALIZING_GAS &&
                GASTRO_ACID !in active.knownVolatileEffectIds.mapTo(hashSetOf()) { canonical(it).orEmpty() }
        }
    }

    private fun canonical(value: String?): String? = value?.substringAfter(':')
        ?.lowercase()?.filter(Char::isLetterOrDigit)?.takeIf(String::isNotEmpty)

    private const val ABILITY_SHIELD = "abilityshield"
    private const val GASTRO_ACID = "gastroacid"
    private const val NEUTRALIZING_GAS = "neutralizinggas"
    private val CANNOT_SUPPRESS = setOf(
        "asoneglastrier", "asonespectrier", "battlebond", "comatose", "disguise", "gulpmissile",
        "iceface", "multitype", "powerconstruct", "rkssystem", "schooling",
        "shieldsdown", "stancechange", "terashift", "zenmode", "zerotohero",
    )
}
