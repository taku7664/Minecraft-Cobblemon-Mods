package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Resolves whether a publicly known held item is currently providing its effect. */
internal object LocalPublicItemState {
    fun activeItemId(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        val item = pokemon?.canonicalKnownHeldItemId ?: return null
        if (LocalPublicFieldMechanics.magicRoomActive(state)) return null
        if (item !in KLUTZ_PROOF_ITEMS &&
            LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == KLUTZ
        ) return null
        return item
    }

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
