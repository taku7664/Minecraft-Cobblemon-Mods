package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Resolves whether a publicly known held item is currently providing its effect. */
internal object LocalPublicItemState {
    fun activeItemId(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        val item = canonical(pokemon?.knownHeldItemId) ?: return null
        if (LocalPublicFieldMechanics.magicRoomActive(state)) return null
        if (item !in KLUTZ_PROOF_ITEMS &&
            LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == KLUTZ
        ) return null
        return item
    }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private const val KLUTZ = "klutz"

    // Showdown's item.ignoreKlutz set. Ability Shield must remain direct in ability-state
    // resolution to avoid an item/ability activation cycle.
    private val KLUTZ_PROOF_ITEMS = setOf(
        "abilityshield",
        "machobrace",
        "poweranklet",
        "powerband",
        "powerbelt",
        "powerbracer",
        "powerlens",
        "powerweight",
    )
}
