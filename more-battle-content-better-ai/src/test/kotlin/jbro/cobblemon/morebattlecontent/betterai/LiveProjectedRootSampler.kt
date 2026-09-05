package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import java.util.Random

/** A fresh projection on every draw; only the sampled branch receives a leaf evaluation. */
internal class LiveProjectedRootSampler(
    private val context: BattleDecisionContext,
    replies: List<BattleActionCandidate>,
    seed: Int,
) {
    private val actions = context.candidates.associateBy { it.actionId }
    private val repliesById = replies.associateBy { it.actionId }
    private val randoms = actions.keys.sorted().flatMap { a -> repliesById.keys.sorted().map { a to it } }
        .mapIndexed { index, pair -> pair to Random(seed.toLong() * 1_000_003L + index) }.toMap()
    private val alreadyFainted = context.state.pokemon.filter { it.fainted }.map { it.battlePokemonId }.toSet()
    var projectionCalls = 0
        private set
    var projectedBranches = 0
        private set
    var leafEvaluations = 0
        private set

    fun sample(action: String, reply: String, shouldContinue: () -> Boolean): LiveRootSample? {
        if (!shouldContinue()) return null
        projectionCalls++
        val projections = PublicSingleTurnProjector.project(context.state, actions.getValue(action),
            repliesById.getValue(reply), context, maxChanceBranchesPerMove = 64,
            chanceEffectMode = ChanceEffectProjectionMode.BRANCH_STATE, shouldContinue = shouldContinue)
        if (projections.isEmpty() || !shouldContinue()) return null
        projectedBranches += projections.size
        val groups = projections.groupBy { it.order }.values
        val orderTotal = groups.sumOf { it.first().orderProbability }
        require(orderTotal > 0.0)
        val weighted = groups.flatMap { group ->
            val total = group.sumOf { it.probability }
            require(total > 0.0)
            group.map { it to (it.probability / total * it.orderProbability / orderTotal) }
        }
        val weights = weighted.map { it.second }
        val total = weights.sum()
        require(total.isFinite() && total > 0.0 && weights.all { it.isFinite() && it >= 0.0 })
        var remaining = randoms.getValue(action to reply).nextDouble() * total
        val selected = weighted.firstOrNull { (_, weight) -> remaining -= weight; remaining < 0.0 }
            ?: weighted.last { it.second > 0.0 }
        val ko = weighted.sumOf { (outcome, weight) ->
            if (outcome.state.pokemon.any { it.side == BattleSide.ALLY && it.fainted &&
                    it.battlePokemonId !in alreadyFainted }) weight / total else 0.0
        }.coerceIn(0.0, 1.0)
        if (!shouldContinue()) return null
        leafEvaluations++
        val value = LocalLookaheadStateEvaluator.evaluate(selected.first.state, context, shouldContinue = shouldContinue)
        return if (shouldContinue()) LiveRootSample(value, ko) else null
    }
}
