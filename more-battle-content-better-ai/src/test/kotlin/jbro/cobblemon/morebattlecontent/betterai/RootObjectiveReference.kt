package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import kotlin.math.abs

internal data class IndividualRootReference(val actionId: String, val evaluation: LocalLookaheadEvaluation)
internal data class RootReferenceResult(
    val whole: LocalLookaheadEvaluation,
    val individual: List<IndividualRootReference>,
    val isolatedRanking: List<String>,
    val mismatches: List<String>,
) {
    val matches: Boolean get() = mismatches.isEmpty()
    val scores: Map<String, Double> get() = whole.ranked.associate { it.outcome.candidate.actionId to it.comparisonValue }
}

/**
 * Test-only full-turn objective reference. Calls production scoring; no reimplementation of
 * turn effects, unknown responses, history, response aggregation or root corrections.
 * A constant clock isolates work completion, NOT runtime latency. Finite per-depth node ceilings
 * still apply. A partial search is rejected, never silently used as an exact depth reference.
 */
internal object RootObjectiveReference {
    fun evaluate(context: BattleDecisionContext, depth: Int, nodeLimit: Int = 200_000): RootReferenceResult {
        require(context.state.format == BattleFormat.SINGLE) { "Doubles candidate pruning needs separate equivalence proof" }
        require(context.deadlineEpochMillis == Long.MAX_VALUE) { "Reference requires offline contexts" }
        require(depth in 1..2 && nodeLimit > 0)
        require(context.candidates.isNotEmpty() && context.candidates.map { it.actionId }.distinct().size == context.candidates.size)
        val profile = BattleTrainerProfile.balanced().copy(
            difficulty = BattleDifficultyProfiles.BOSS.copy(lookaheadPlies = depth))
        val base = LocalBattleActionPolicy.rank(context, null, profile)
        fun run(ranks: List<LocalBattleActionRank>): LocalLookaheadEvaluation {
            val result = LocalRecursiveLookaheadEvaluator.evaluate(ranks, context, profile,
                clockMillis = { 0L }, budget = LocalLookaheadBudget(1_000L, nodeLimit, 64))
            check(!result.truncated && result.depthCompleted == depth) {
                "Incomplete reference: requested=$depth completed=${result.depthCompleted} nodes=${result.nodesVisited}"
            }
            check(result.ranked.all { it.comparisonValue.isFinite() && it.lookaheadUtility.isFinite() &&
                it.executionProbability.isFinite() && it.worstResponseHpRetention.isFinite() })
            return result
        }
        val whole = run(base)
        // Reverse candidate order and use a fresh production evaluator/cache per root.
        // Keep the original complete public context, including candidates and memory.
        val individual = base.reversed().map { rank ->
            IndividualRootReference(rank.outcome.candidate.actionId, run(listOf(rank)))
        }
        val separated = individual.associate { it.actionId to it.evaluation.ranked.single() }
        val mismatches = mutableListOf<String>()
        for (rank in whole.ranked) {
            val id = rank.outcome.candidate.actionId
            val isolated = separated.getValue(id)
            fun compare(field: String, a: Double, b: Double) {
                if (abs(a - b) > 1e-9) mismatches += "$id:$field:$a:$b"
            }
            compare("comparisonValue", rank.comparisonValue, isolated.comparisonValue)
            compare("lookaheadUtility", rank.lookaheadUtility, isolated.lookaheadUtility)
            compare("executionProbability", rank.executionProbability, isolated.executionProbability)
            compare("worstResponseHpRetention", rank.worstResponseHpRetention, isolated.worstResponseHpRetention)
        }
        val isolatedRanking = LocalBattleActionPolicy.sort(base.map { separated.getValue(it.outcome.candidate.actionId) })
            .map { it.outcome.candidate.actionId }
        if (isolatedRanking != whole.ranked.map { it.outcome.candidate.actionId }) mismatches += "ranking"
        return RootReferenceResult(whole, individual, isolatedRanking, mismatches)
    }

    /** Recursive ranking score units, not board bars, win probability or sampled-mean regret. */
    fun loss(scores: Map<String, Double>, chosen: String?): Double? {
        require(scores.isNotEmpty() && scores.values.all(Double::isFinite))
        if (chosen == null) return null
        require(chosen in scores)
        return (scores.values.max() - scores.getValue(chosen)).coerceAtLeast(0.0)
    }
}
