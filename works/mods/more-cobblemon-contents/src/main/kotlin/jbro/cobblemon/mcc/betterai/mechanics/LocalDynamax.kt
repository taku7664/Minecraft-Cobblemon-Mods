package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A Dynamaxed Pokemon as the legacy calculation reads it: the `dynamax` volatile Showdown starts and ends publicly, and
 * a `dynamaxturns:N` count of the turns it has left (this one included), set at the start and lowered after each turn
 * ([LocalPersistentMoveState.afterResidual], the observer's `advance`).
 *
 * Dynamax doubles maximum and current HP and keeps the share when it ends (Showdown's `getUndynamaxedHP`), so in the
 * HP-share units every calculation uses, a Dynamaxed Pokemon takes half of each hit, for as long as it lasts.
 */
internal object LocalDynamax {
    const val VOLATILE = "dynamax"
    const val TURNS = "dynamaxturns:"
    /** Turns a Dynamax lasts, the one it starts on included. */
    const val DURATION = 3
    /** The share of a hit's HP fraction a Dynamaxed Pokemon takes: its maximum HP is doubled. */
    const val DAMAGE_SHARE = 0.5

    fun active(pokemon: BattlePokemonStateView?): Boolean =
        pokemon?.knownVolatileEffectIds?.any { PublicIds.canonical(it) == VOLATILE } == true

    /** [DAMAGE_SHARE] while [pokemon] is Dynamaxed, otherwise 1. */
    fun damageShare(pokemon: BattlePokemonStateView?): Double = if (active(pokemon)) DAMAGE_SHARE else 1.0

    /** [pokemon] Dynamaxed from now, for [DURATION] turns; unchanged when it already is. */
    fun start(pokemon: BattlePokemonStateView): BattlePokemonStateView =
        if (active(pokemon)) pokemon
        else pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds.filterNot { it.startsWith(TURNS) }.toSet() +
            VOLATILE + "$TURNS$DURATION")
}
