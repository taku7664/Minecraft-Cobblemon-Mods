package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * The chance [BattleActionCandidate]'s user gets its move off this turn: every foe that moves before it may knock it out
 * first. Read on the candidate's own board (its Terastallization, Mega Evolution or Dynamax applied first, as they come
 * before any move), each foe's likeliest knockout of the user played as an action against the candidate's (priority on
 * both sides, abilities such as Prankster, Speed, Quick Claw and Quick Draw on both sides), at the chance the foe moves
 * at all and aims at the user (a spread hit lands whatever it aims at; a single one at one of this side's Pokemon in
 * front), two hits together counted ([LocalDynamaxGuard.knockout]).
 *
 * A status move's whole worth is in being used: hazards (261), a screen (225), Glare (162) and Trick Room (61) were
 * chosen by Pokemon a faster foe knocked out first (league run, 2026-10-19).
 */
internal object LocalActingChance {
    fun of(candidate: BattleActionCandidate, context: BattleDecisionContext, @Suppress("UNUSED_PARAMETER") scores: MatchupScores): Double {
        val board = context.copy(state = LocalMechanicActivationProjector.beforeMoves(context.state, BattleSide.ALLY, candidate))
        val state = board.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 1.0
        val allies = state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .coerceAtLeast(1)
        val cache = LocalProjectedActionCalculationCache()
        val hits = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .mapNotNull { foe ->
                val faced = LocalMatchupPosition.face(board, user, foe, cache) ?: return@mapNotNull null
                val hit = LocalMatchupScoreCalculator.moveMatchups(faced, foe.battlePokemonId, user.battlePokemonId, cache)
                    .maxByOrNull { it.score.knockoutChanceWithin(1) } ?: return@mapNotNull null
                val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, candidate, BattleSide.OPPONENT, hit.action) ?: 0.5
                val pattern = hit.action.moveDetails?.targetPattern
                val aim = if (pattern == BattleMoveTargetPattern.ALL_OPPONENTS || pattern == BattleMoveTargetPattern.ALL_ADJACENT) 1.0 else 1.0 / allies
                LocalDynamaxGuard.IncomingHit(hit.score, LocalKnockoutWorth.actsThisTurn(foe) * (1.0 - first), aim)
            }
        if (hits.isEmpty()) return 1.0
        return 1.0 - LocalDynamaxGuard.knockout(hits, user.hpFraction).coerceIn(0.0, 1.0)
    }
}
