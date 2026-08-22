package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * Decides whether a projected turn is bad enough to skip only its deeper continuation.
 *
 * The root action and the opponent response are never removed here. Their projected immediate
 * result still participates in the response aggregate, so a move that is bad into a stay but good
 * into a predicted switch remains represented. Any knockout remains tactically unstable until the
 * forced replacement and its following action are searched, regardless of the lost Pokemon's HP.
 */
internal object LocalTurnBranchPruner {
    fun shouldStopContinuation(
        immediateBoardDelta: Double,
        depthRemaining: Int,
        newlyLostAllyHpBefore: Double?,
    ): Boolean {
        if (depthRemaining <= 1) return false
        if (!immediateBoardDelta.isFinite() || immediateBoardDelta > CATASTROPHIC_TURN_DELTA) return false
        if (newlyLostAllyHpBefore != null) return false
        return true
    }

    fun newlyLostAllyHpBefore(
        before: BattleStateView,
        after: BattleStateView,
    ): Double? {
        val afterById = after.pokemon.associateBy { it.battlePokemonId }
        return before.pokemon.asSequence()
            .filter {
                it.side == BattleSide.ALLY &&
                    !it.fainted &&
                    it.hpFraction > 0.0
            }
            .filter { previous ->
                afterById[previous.battlePokemonId]?.let { next -> next.fainted || next.hpFraction <= 0.0 } == true
            }
            .minOfOrNull { it.hpFraction }
    }

    // Board units: losing the living-Pokemon value alone costs 2.0 before HP and pressure changes.
    private const val CATASTROPHIC_TURN_DELTA = -1.75
}
