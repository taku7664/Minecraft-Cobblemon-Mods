package jbro.cobblemon.morebattlecontent.betterai.search

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
    fun shouldStopBranch(
        immediateTurnDelta: Double,
        depthRemaining: Int,
        newlyLostAllyHpBefore: Double?,
        /**
         * Raises the bar for abandoning a continuation, as a board-unit offset.
         *
         * Zero is the shipped behaviour: only a turn that already lost close to a full health bar is
         * dropped. Raising it prunes more, and prunes exactly the branches a turn-order or survival
         * play lives in - those are the ones whose immediate delta is small. Exposed so that trade
         * can be measured rather than argued about.
         */
        thresholdOffset: Double = 0.0,
    ): Boolean {
        if (depthRemaining <= 0 || !immediateTurnDelta.isFinite()) return false
        if (newlyLostAllyHpBefore != null) return false
        return immediateTurnDelta <= threshold(depthRemaining) + thresholdOffset
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

    private fun threshold(depthRemaining: Int): Double = when (depthRemaining) {
        1 -> -0.85
        2 -> -1.00
        3 -> -1.15
        else -> -1.30
    }
}
