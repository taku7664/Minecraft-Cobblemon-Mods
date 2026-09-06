package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * Shared material units for turn deltas and leaf positions, including actual removals.
 * One unit is one full HP bar; surviving Pokemon retain two additional units. Unseen living
 * Pokemon keep the existing full-HP estimate, not fabricated species or hidden set knowledge.
 * Tactical ranking bonuses and attack-pressure weights are intentionally not owned here.
 */
internal object LocalBoardMaterial {
    fun evaluate(state: BattleStateView): Double =
        sideValue(state, BattleSide.ALLY) - sideValue(state, BattleSide.OPPONENT)

    private fun sideValue(state: BattleStateView, side: BattleSide): Double {
        val knownLiving = state.pokemon.filter {
            it.side == side && !it.fainted && it.hpFraction > 0.0
        }
        val unseenLiving = (state.remainingPokemonBySide.getValue(side) - knownLiving.size).coerceAtLeast(0)
        return knownLiving.sumOf { it.hpFraction + LIVING_POKEMON_VALUE } +
            unseenLiving * (1.0 + LIVING_POKEMON_VALUE)
    }

    private const val LIVING_POKEMON_VALUE = 2.0
}
