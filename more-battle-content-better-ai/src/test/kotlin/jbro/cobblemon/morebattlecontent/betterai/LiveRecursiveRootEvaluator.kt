package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator

/** Live public recursive work, not a lookup into the full-depth referee table. Offline clock only. */
internal class LiveRecursiveRootEvaluator(private val context: BattleDecisionContext) {
    init {
        require(context.state.format == BattleFormat.SINGLE && context.deadlineEpochMillis == Long.MAX_VALUE)
        require(context.candidates.isNotEmpty() && context.candidates.map { it.actionId }.distinct().size == context.candidates.size)
    }
    private val profiles = (1..2).associateWith { depth -> BattleTrainerProfile.balanced().copy(
        difficulty = BattleDifficultyProfiles.BOSS.copy(lookaheadPlies = depth)) }
    private val ranks = profiles.mapValues { (_, profile) ->
        LocalBattleActionPolicy.rank(context, null, profile).associateBy { it.outcome.candidate.actionId }
    }

    fun evaluate(actionId: String, depth: Int, remainingNodes: Int): RootDeepeningReading {
        require(depth in 1..2 && remainingNodes > 0 && actionId in ranks.getValue(depth))
        // Production resets its counter each iterative depth and counts the first denied check.
        // Reserve that extra check for EACH depth so even interrupted work fits the global budget.
        val perDepthLimit = remainingNodes / depth - 1
        if (perDepthLimit < 1) return RootDeepeningReading(null, 0)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(listOf(ranks.getValue(depth).getValue(actionId)),
            context, profiles.getValue(depth), clockMillis = { 0L },
            budget = LocalLookaheadBudget(1_000L, perDepthLimit, 64))
        check(result.nodesVisited <= remainingNodes)
        if (result.truncated || result.depthCompleted != depth) return RootDeepeningReading(null, result.nodesVisited)
        val rank = result.ranked.single()
        return RootDeepeningReading(rank.comparisonValue, result.nodesVisited, rank.executionProbability,
            rank.worstResponseHpRetention, result.publicResponseIncomplete)
    }
}
