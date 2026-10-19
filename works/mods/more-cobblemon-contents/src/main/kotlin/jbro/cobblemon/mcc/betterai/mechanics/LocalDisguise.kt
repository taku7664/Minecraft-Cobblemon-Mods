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
        // The ability and the form it breaks into, read from the ability's code (Disguise: busted; no ability named).
        val broken = shield(state, pokemon)?.brokenForm ?: return false
        if (listOfNotNull(pokemon.formId, pokemon.speciesId).any { PublicIds.canonical(it).contains(broken) }) return false
        return state.observedEvents.none { event ->
            event.actorPokemonId == pokemon.battlePokemonId && event.publicSourceEffectId?.let(PublicIds::canonical)?.contains(broken) == true
        }
    }

    /** The share of maximum HP the shield costs as it breaks (Disguise: an eighth), from the ability's code. */
    fun chip(state: BattleStateView, pokemon: BattlePokemonStateView): Double = shield(state, pokemon)?.chip ?: CHIP

    private fun shield(state: BattleStateView, pokemon: BattlePokemonStateView) =
        jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects.firstHitShield(LocalPublicAbilityState.effectiveKnownAbility(state, pokemon))

    const val CHIP = 0.125
}
