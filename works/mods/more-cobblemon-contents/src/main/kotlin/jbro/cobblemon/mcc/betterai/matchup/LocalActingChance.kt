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
 * A foe that may still Dynamax this turn is read with its Max Move too ([LocalFoeDynamaxThreat]).
 *
 * A status move's whole worth is in being used: hazards (261), a screen (225), Glare (162) and Trick Room (61) were
 * chosen by Pokemon a faster foe knocked out first (league run, 2026-10-19).
 */
internal object LocalActingChance {
    /**
     * [context] is the board before any mechanic (the Mega rules board would Mega Evolve a candidate that does not);
     * [candidate] the whole turn, a doubles pair included, so every mechanic in it comes first; [actorSlot] the user read.
     */
    fun of(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        @Suppress("UNUSED_PARAMETER") scores: MatchupScores,
        actorSlot: Int? = candidate.actorSlot,
        /** Foes left out: an attack's own target, whose threat its knockout credit already reads (LocalPokemonWorth.knockoutScore). */
        ignoring: Set<java.util.UUID> = emptySet(),
    ): Double {
        val board = context.copy(state = LocalMechanicActivationProjector.beforeMoves(context.state, BattleSide.ALLY, candidate))
        val state = board.state
        val part = if (candidate.kind == jbro.cobblemon.mcc.internal.ai.BattleActionKind.COMPOSITE)
            candidate.componentActions.firstOrNull { it.actorSlot == actorSlot } ?: return 1.0 else candidate
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 1.0
        val allies = state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .coerceAtLeast(1)
        val cache = LocalProjectedActionCalculationCache()
        fun incoming(foe: jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView, hit: LocalMatchupScoreCalculator.ScoredMove): LocalDynamaxGuard.IncomingHit {
            val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, part, BattleSide.OPPONENT, hit.action) ?: 0.5
            val pattern = hit.action.moveDetails?.targetPattern
            val aim = if (pattern == BattleMoveTargetPattern.ALL_OPPONENTS || pattern == BattleMoveTargetPattern.ALL_ADJACENT) 1.0 else 1.0 / allies
            return LocalDynamaxGuard.IncomingHit(hit.score, LocalKnockoutWorth.actsThisTurn(foe, state) * (1.0 - first), aim)
        }
        // Each foe's likeliest knockout, and with its Max Move where it may still Dynamax and that knocks out more
        // ([LocalFoeDynamaxThreat]).
        val reads = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                it.battlePokemonId !in ignoring }
            .mapNotNull { foe ->
                val faced = LocalMatchupPosition.face(board, user, foe, cache) ?: return@mapNotNull null
                val plain = LocalMatchupScoreCalculator.moveMatchups(faced, foe.battlePokemonId, user.battlePokemonId, cache)
                    .maxByOrNull { it.score.knockoutChanceWithin(1) } ?: return@mapNotNull null
                val max = LocalFoeDynamaxThreat.bestHit(context, board, user, foe, cache)
                    ?.takeIf { it.score.knockoutChanceWithin(1) > plain.score.knockoutChanceWithin(1) }
                incoming(foe, plain) to max?.let { incoming(foe, it) }
            }
        if (reads.isEmpty()) return 1.0
        // A side Dynamaxes one Pokemon at most: every foe's plain hit, or one of them Dynamaxed, the worst of those.
        val plainHits = reads.map { it.first }
        val worst = (listOf(plainHits) + reads.indices.mapNotNull { index ->
            reads[index].second?.let { max -> plainHits.toMutableList().also { it[index] = max } }
        }).maxOf { LocalDynamaxGuard.knockout(it, user.hpFraction) }
        return 1.0 - worst.coerceIn(0.0, 1.0)
    }
}
