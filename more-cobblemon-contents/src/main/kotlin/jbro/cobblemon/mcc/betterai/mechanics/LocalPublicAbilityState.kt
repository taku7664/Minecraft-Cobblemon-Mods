package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Whether a publicly known ability is active after deterministic suppression rules. */
internal object LocalPublicAbilityState {
    fun effectiveKnownAbility(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        val ability = canonical(pokemon?.knownAbilityId)
        return ability?.takeIf { pokemon != null && isActive(state, pokemon, it) }
    }

    fun isActive(state: BattleStateView, pokemon: BattlePokemonStateView, abilityId: String): Boolean {
        val ability = canonical(abilityId) ?: return false
        if (ability in CANNOT_SUPPRESS) return true
        // Called at every search node for every ability check, so no sets are built here.
        if (hasGastroAcid(pokemon)) return false
        val itemProtected = !LocalPublicFieldMechanics.magicRoomActive(state) &&
            canonical(pokemon.knownHeldItemId) == ABILITY_SHIELD
        if (itemProtected || ability == NEUTRALIZING_GAS) return true
        return state.pokemon.none { active ->
            active.battlePokemonId != pokemon.battlePokemonId && active.activeSlot != null &&
                !active.fainted && active.hpFraction > 0.0 &&
                canonical(active.knownAbilityId) == NEUTRALIZING_GAS &&
                !hasGastroAcid(active)
        }
    }

    private fun hasGastroAcid(pokemon: BattlePokemonStateView): Boolean =
        pokemon.knownVolatileEffectIds.any { canonical(it) == GASTRO_ACID }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)?.takeIf(String::isNotEmpty)

    private const val ABILITY_SHIELD = "abilityshield"
    private const val GASTRO_ACID = "gastroacid"
    private const val NEUTRALIZING_GAS = "neutralizinggas"
    private val CANNOT_SUPPRESS = setOf(
        "asoneglastrier", "asonespectrier", "battlebond", "comatose", "disguise", "gulpmissile",
        "iceface", "multitype", "powerconstruct", "rkssystem", "schooling",
        "shieldsdown", "stancechange", "terashift", "zenmode", "zerotohero",
    )
}
