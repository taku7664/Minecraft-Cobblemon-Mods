package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Rules out a status move that only spends a turn: its [StatusMoveMatchupScore] is [WASTED] or worse.
 *
 * Only where the exchange holds the move's whole value, so the rule can be trusted over the search: one
 * opponent it aims at, with nothing but effects the exchange plays out (a burn or paralysis on the target,
 * certain stat drops on it). In doubles the score also counts what the move does for the partner against
 * that target. A pivot, a spread move, a field or a hazard are outside that exchange; those moves are left
 * to the search. A doubles turn is ruled out when any of its slots is.
 */
internal object LocalStatusMoveTriage {
    const val WASTED = -0.25
    const val REASON = "status_wasted"

    fun wasted(candidate: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Boolean =
        parts(candidate).any { wastedMove(it, context, scores) }

    private fun wastedMove(move: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Boolean {
        if (!judgeableMove(move, context)) return false
        val state = context.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == move.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return false
        val target = target(move, context) ?: return false
        val moveId = PublicIds.canonical(move.moveId ?: return false)
        val score = scores.statusMoves(user.battlePokemonId, target.battlePokemonId).firstOrNull { it.moveId == moveId } ?: return false
        return score.score <= WASTED
    }

    /** Whether the rule may judge [candidate] at all. */
    fun judgeable(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean =
        parts(candidate).any { judgeableMove(it, context) }

    private fun judgeableMove(move: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        if (move.kind != BattleActionKind.USE_MOVE) return false
        val details = move.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        if (target(move, context) == null) return false
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.target == BattleMoveEffectTarget.SELECTED_TARGET && (
                effect.kind == BattleMoveEffectKind.STATUS && effect.valueId?.let(PublicIds::canonical) in setOf("brn", "par") ||
                    effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.statStages.values.all { it < 0 }
                )
        }
    }

    /** The one opponent [move] aims at: the only one in singles, the declared one in doubles. */
    private fun target(move: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? {
        val state = context.state
        fun standing(it: BattlePokemonStateView) = it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        return when (state.format) {
            BattleFormat.SINGLE -> state.pokemon.singleOrNull(::standing)
            BattleFormat.DOUBLE -> {
                if (move.moveDetails?.targetPattern !in SINGLE_TARGET) return null
                val declared = move.targets.singleOrNull()?.takeIf { it.side == BattleSide.OPPONENT } ?: return null
                state.pokemon.firstOrNull { standing(it) && it.activeSlot == declared.slot }
            }
        }
    }

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private val SINGLE_TARGET = setOf(BattleMoveTargetPattern.SELECTED, BattleMoveTargetPattern.SELECTED_OPPONENT)
}
