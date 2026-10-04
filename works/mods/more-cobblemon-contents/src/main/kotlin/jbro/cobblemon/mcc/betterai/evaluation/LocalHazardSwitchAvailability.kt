package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.*

/** Discounts existing hazard value by surviving reserves; never raises the original value. */
internal object LocalHazardSwitchAvailability {
    fun isHazardMove(candidate: BattleActionCandidate): Boolean =
        candidate.moveId?.let(PublicIds::canonical) in HAZARDS

    fun fraction(state: BattleStateView, side: BattleSide, source: BattleDecisionContext? = null): Double {
        val remaining = state.remainingPokemonBySide.getValue(side)
        val active = state.pokemon.count { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        val reserves = (remaining - active).coerceAtLeast(0)
        if (reserves == 0) return 0.0
        // Public fainted identities remain in snapshots. Together with the remaining count they
        // retain the participating team size even when living opponents have not been revealed.
        val observedTeamSize = remaining + state.pokemon.count { it.side == side && it.fainted }
        val participatingTeamSize = if (side == BattleSide.OPPONENT) {
            source?.opponentTeamPreview?.selectionSize ?: observedTeamSize
        } else observedTeamSize
        val activeCapacity = if (state.format == BattleFormat.DOUBLE) 2 else 1
        val reserveCapacity = (participatingTeamSize.coerceAtLeast(remaining) - activeCapacity).coerceAtLeast(0)
        return if (reserveCapacity == 0) 0.0 else (reserves.toDouble() / reserveCapacity).coerceIn(0.0, 1.0)
    }

    private val HAZARDS = setOf("stealthrock", "spikes", "toxicspikes", "stickyweb")
}
