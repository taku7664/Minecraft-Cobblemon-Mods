package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

/** Whether a Substitute stands and whether a move gets past it, for readers outside the hit sequence. */
internal object LocalSubstituteRules {
    fun present(pokemon: BattlePokemonStateView): Boolean = "substitute" in pokemon.canonicalKnownVolatileEffectIds

    /** Infiltrator, a sound move or a move flagged to bypass it reaches the body. */
    fun bypasses(action: BattleActionCandidate, actor: BattlePokemonStateView?, state: BattleStateView): Boolean =
        LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "infiltrator" ||
            action.moveDetails?.effects?.mechanicFlags.orEmpty().any { PublicIds.canonical(it) in setOf("sound", "bypasssub") }
}
