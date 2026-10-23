package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/** Showdown's public/inferred truant volatile is the next BeforeMove's loafing phase, not turn parity. */
internal object LocalTruant {
    fun active(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean =
        LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == "truant"

    fun loafs(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean =
        active(state, pokemon) && "truant" in pokemon.canonicalKnownVolatileEffectIds
}
