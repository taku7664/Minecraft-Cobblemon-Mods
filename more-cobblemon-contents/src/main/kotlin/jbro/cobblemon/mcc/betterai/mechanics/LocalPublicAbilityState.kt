package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Whether a publicly known ability is active after deterministic suppression rules. */
internal object LocalPublicAbilityState {
    fun effectiveKnownAbility(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        val ability = pokemon?.canonicalKnownAbilityId ?: return null
        return ability.takeIf { activeCanonical(state, pokemon, it) }
    }

    fun isActive(state: BattleStateView, pokemon: BattlePokemonStateView, abilityId: String): Boolean {
        val ability = canonical(abilityId) ?: return false
        return activeCanonical(state, pokemon, ability)
    }

    private fun activeCanonical(state: BattleStateView, pokemon: BattlePokemonStateView, ability: String): Boolean {
        if (ability in CANNOT_SUPPRESS) return true
        // Called at every search node for every ability check, so no sets are built here.
        if (hasGastroAcid(pokemon)) return false
        val itemProtected = pokemon.canonicalKnownHeldItemId == ABILITY_SHIELD &&
            !LocalPublicFieldMechanics.magicRoomActive(state)
        if (itemProtected || ability == NEUTRALIZING_GAS) return true
        return neutralizingGasHolders[state].none { it != pokemon.battlePokemonId }
    }

    /** Active Pokemon whose public Neutralizing Gas works, found once per state. */
    private val neutralizingGasHolders = LocalStateMemo { state ->
        state.pokemon.filter { active ->
            active.activeSlot != null && !active.fainted && active.hpFraction > 0.0 &&
                active.canonicalKnownAbilityId == NEUTRALIZING_GAS && !hasGastroAcid(active)
        }.map { it.battlePokemonId }
    }

    private fun hasGastroAcid(pokemon: BattlePokemonStateView): Boolean =
        GASTRO_ACID in pokemon.canonicalKnownVolatileEffectIds

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
