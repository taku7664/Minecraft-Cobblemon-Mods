package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Whether a Pokemon's Disguise is still up: the ability known (or its only possible one) and no busted form seen, in its
 * form or species or in a public event naming the busted form. The game's state kept a busted Mimikyu's species as
 * Mimikyu, the HP change carrying the busted form (dev server, 2026-10-20).
 */
internal object LocalDisguise {
    fun intact(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean {
        if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)?.let(PublicIds::canonical) != DISGUISE) return false
        if (listOfNotNull(pokemon.formId, pokemon.speciesId).any { PublicIds.canonical(it).contains(BUSTED) }) return false
        return state.observedEvents.none { event ->
            event.actorPokemonId == pokemon.battlePokemonId && event.publicSourceEffectId?.let(PublicIds::canonical)?.contains(BUSTED) == true
        }
    }

    const val CHIP = 0.125
    private const val DISGUISE = "disguise"
    private const val BUSTED = "busted"
}
