package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Resolves whether a publicly known held item is currently providing its effect. */
internal object LocalPublicItemState {
    fun activeItemId(state: BattleStateView, pokemon: BattlePokemonStateView?): String? {
        pokemon ?: return null
        val item = pokemon.canonicalKnownHeldItemId ?: return null
        if (LocalPublicFieldMechanics.magicRoomActive(state)) return null
        if (item !in KLUTZ_PROOF_ITEMS &&
            LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == KLUTZ
        ) return null
        // Unnerve and As One on an active foe keep a berry from being eaten.
        if (item.endsWith(BERRY) && state.pokemon.any {
                it.side != pokemon.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                    LocalPublicAbilityState.effectiveKnownAbility(state, it) in UNNERVE_ABILITIES
            }) return null
        return item
    }

    private const val KLUTZ = "klutz"
    private const val BERRY = "berry"
    private val UNNERVE_ABILITIES = setOf("unnerve", "asoneglastrier", "asonespectrier")

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
