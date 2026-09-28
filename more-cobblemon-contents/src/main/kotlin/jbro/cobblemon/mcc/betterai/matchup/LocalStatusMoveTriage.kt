package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Rules out a status move that only spends a turn: its [StatusMoveMatchupScore] is [WASTED] or worse.
 *
 * Only where the one-on-one exchange holds the move's whole value, so the rule can be trusted over the
 * search: singles, against the one opponent, with nothing but effects the exchange plays out (a burn or
 * paralysis on the target, certain stat drops on it). A pivot, a doubles partner the move protects, a
 * field or a hazard are outside that exchange; those moves are left to the search.
 */
internal object LocalStatusMoveTriage {
    const val WASTED = -0.25
    const val REASON = "status_wasted"

    fun wasted(candidate: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Boolean {
        if (!judgeable(candidate, context)) return false
        val state = context.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return false
        val target = state.pokemon.singleOrNull {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return false
        val moveId = PublicIds.canonical(candidate.moveId ?: return false)
        val score = scores.statusMoves(user.battlePokemonId, target.battlePokemonId).firstOrNull { it.moveId == moveId } ?: return false
        return score.score <= WASTED
    }

    /** Whether the rule may judge [candidate] at all. */
    fun judgeable(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        if (context.state.format != BattleFormat.SINGLE || candidate.kind != BattleActionKind.USE_MOVE) return false
        val details = candidate.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.target == BattleMoveEffectTarget.SELECTED_TARGET && (
                effect.kind == BattleMoveEffectKind.STATUS && effect.valueId?.let(PublicIds::canonical) in setOf("brn", "par") ||
                    effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.statStages.values.all { it < 0 }
                )
        }
    }
}
