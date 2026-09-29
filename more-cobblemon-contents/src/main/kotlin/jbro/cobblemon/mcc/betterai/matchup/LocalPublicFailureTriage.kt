package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Rules out a move the public state says cannot work, the same check the search applies to its own
 * turns: an attack into a public type immunity (Fake Out into a Ghost), a major status on a Pokemon that
 * already has one, weather or terrain that is already up. A doubles turn is ruled out when any of its
 * slots is. A singles move without a declared target aims at the one opponent.
 */
internal object LocalPublicFailureTriage {
    const val REASON = "publicly_fails"

    fun fails(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
        return parts.any { fails(it, context.state) }
    }

    private fun fails(move: BattleActionCandidate, state: BattleStateView): Boolean {
        if (move.kind != BattleActionKind.USE_MOVE) return false
        val actor = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == move.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return false
        if (state.format == BattleFormat.SINGLE && move.targets.isEmpty()) {
            val opponent = state.pokemon.singleOrNull {
                it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
            return PublicFutureActionFactory.publiclyFails(state, BattleSide.ALLY, actor, move, opponent)
        }
        return PublicFutureActionFactory.publiclyFails(state, BattleSide.ALLY, actor, move)
    }
}
