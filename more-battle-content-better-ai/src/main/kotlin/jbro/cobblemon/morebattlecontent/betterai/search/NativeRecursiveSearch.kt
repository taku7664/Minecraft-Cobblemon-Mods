package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRootActionMapping
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRootActionMatcher
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeSearchPosition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree

/** Identifies one public opponent hypothesis evaluated with one common random sample. */
internal data class NativeSearchWorldKey(
    val hypothesisId: String,
    val randomSampleIndex: Int,
    /** Distinguishes publicly indistinguishable native descendants of the same sampled world. */
    val lineage: String = "",
) {
    init {
        require(hypothesisId.isNotBlank())
        require(randomSampleIndex >= 0)
    }
}

internal enum class NativeSearchTerminationReason { COMPLETED, DEADLINE, NODE_BUDGET }

internal data class NativeRootActionValue(
    val action: BattleActionCandidate,
    val value: Double,
)

/** One root iteration that completed for every candidate before search stopped. */
internal data class NativeCompletedSearchDepth(
    val depth: Int,
    val rootValues: List<NativeRootActionValue>,
) {
    init {
        require(depth > 0)
        require(rootValues.map { it.action.actionId }.distinct().size == rootValues.size)
        require(rootValues.all { it.value.isFinite() })
    }
}

internal data class NativeRecursiveSearchResult(
    val rootValues: List<NativeRootActionValue>,
    val completedIterations: List<NativeCompletedSearchDepth>,
    val nodesVisited: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val terminationReason: NativeSearchTerminationReason,
) {
    init {
        require(nodesVisited >= 0)
        require(depthCompleted >= 0)
        require(completedIterations.map(NativeCompletedSearchDepth::depth) == (1..depthCompleted).toList()) {
            "Completed native iterations must be contiguous through depthCompleted"
        }
        require(rootValues == completedIterations.lastOrNull()?.rootValues.orEmpty()) {
            "Native root values must come from the last fully completed iteration"
        }
    }

    val bestAction: BattleActionCandidate? = rootValues.maxByOrNull(NativeRootActionValue::value)?.action
}

internal data class NativeProductSearchAttempt(
    val mapping: NativeRootActionMapping,
    val result: NativeRecursiveSearchResult?,
)

/** Iterative-deepening minimax whose state transitions come only from native Showdown snapshots. */
internal class NativeRecursiveSearch(
    private val tree: NativeShowdownSearchTree,
    private val world: NativeSearchWorldKey,
    private val evaluate: (BattleStateView) -> Double,
    private val nodeLimit: Int,
    private val shouldContinue: () -> Boolean = { true },
    private val cacheEntryLimit: Int = DEFAULT_CACHE_ENTRY_LIMIT,
) {
    private var nodesVisited = 0
    private var truncated = false
    private var terminationReason = NativeSearchTerminationReason.COMPLETED
    // A deterministic Showdown transition does not depend on the search horizon. Values do, so
    // only the value key carries depthRemaining. Snapshot keys can be large for full teams, so
    // both decision-local caches evict deterministically rather than retaining every visited node.
    private val branchCache = object : LinkedHashMap<BranchKey, NativeSearchPosition>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<BranchKey, NativeSearchPosition>?,
        ): Boolean = size > cacheEntryLimit
    }
    private val valueCache = object : LinkedHashMap<ValueKey, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ValueKey, Double>?): Boolean =
            size > cacheEntryLimit
    }

    init {
        require(nodeLimit > 0)
        require(cacheEntryLimit > 0)
    }

    fun evaluate(maxDepth: Int): NativeRecursiveSearchResult {
        return evaluateNative(maxDepth, tree.actions(tree.root, BattleSide.ALLY))
    }

    fun evaluateProduct(
        productActions: List<BattleActionCandidate>,
        maxDepth: Int,
    ): NativeProductSearchAttempt {
        val mapping = NativeRootActionMatcher.match(
            tree.root.state.format,
            productActions,
            tree.actions(tree.root, BattleSide.ALLY),
        )
        if (!mapping.complete) return NativeProductSearchAttempt(mapping, null)
        val nativeActions = productActions.map { product ->
            mapping.productToNative.getValue(product.actionId)
        }
        val nativeResult = evaluateNative(maxDepth, nativeActions)
        val productByNativeId = mapping.productToNative.entries.associate { (productId, native) ->
            native.actionId to productActions.single { it.actionId == productId }
        }
        return NativeProductSearchAttempt(
            mapping = mapping,
            result = nativeResult.copy(
                rootValues = restoreProductActions(nativeResult.rootValues, productByNativeId),
                completedIterations = nativeResult.completedIterations.map { iteration ->
                    iteration.copy(
                        rootValues = restoreProductActions(iteration.rootValues, productByNativeId),
                    )
                },
            ),
        )
    }

    private fun restoreProductActions(
        values: List<NativeRootActionValue>,
        productByNativeId: Map<String, BattleActionCandidate>,
    ): List<NativeRootActionValue> = values.map { rootValue ->
        NativeRootActionValue(
            action = requireNotNull(productByNativeId[rootValue.action.actionId]),
            value = rootValue.value,
        )
    }

    private fun evaluateNative(
        maxDepth: Int,
        rootActions: List<BattleActionCandidate>,
    ): NativeRecursiveSearchResult {
        require(maxDepth > 0)
        var accepted = emptyList<NativeRootActionValue>()
        val completedIterations = mutableListOf<NativeCompletedSearchDepth>()
        var completedDepth = 0
        for (depth in 1..maxDepth) {
            val iteration = evaluateRootDepth(rootActions, depth)
            if (truncated || iteration == null) break
            accepted = iteration
            completedDepth = depth
            completedIterations += NativeCompletedSearchDepth(depth, iteration)
        }
        return NativeRecursiveSearchResult(
            rootValues = accepted,
            completedIterations = completedIterations,
            nodesVisited = nodesVisited,
            depthCompleted = completedDepth,
            truncated = truncated,
            terminationReason = terminationReason,
        )
    }

    private fun evaluateRootDepth(
        rootActions: List<BattleActionCandidate>,
        depth: Int,
    ): List<NativeRootActionValue>? {
        val opponentActions = tree.actions(tree.root, BattleSide.OPPONENT)
        if (rootActions.isEmpty() || opponentActions.isEmpty()) return emptyList()
        val rootHpAdvantage = hpAdvantage(tree.root)
        val values = mutableListOf<NativeRootActionValue>()
        for (allyAction in rootActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(tree.root, allyAction, opponentAction) ?: return null
                // The deep leaf can give the same final board to an immediate attack and a wasted
                // recovery turn. Retain first-turn HP progress as a separate tempo term. A
                // shallow search already sees that progress directly, so only deeper roots need it.
                // Knockout/living bonuses stay in the regular evaluation; adding them here again
                // would make a quick KO worth several extra health bars.
                val tempo = if (depth > 1) {
                    (hpAdvantage(child) - rootHpAdvantage) * ROOT_TEMPO_WEIGHT
                } else 0.0
                val value = projectedValue(child, depth - 1, worstResponse - tempo) ?: return null
                val rootValue = value + tempo
                worstResponse = minOf(worstResponse, rootValue)
            }
            values += NativeRootActionValue(allyAction, worstResponse)
        }
        return values
    }

    private fun positionValue(
        position: NativeSearchPosition,
        depthRemaining: Int,
        upperBound: Double,
    ): Double? {
        if (!timeAvailable()) return null
        if (depthRemaining <= 0 || position.frame.ended) return evaluate(position.state) + position.recoilCredit
        val key = ValueKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            depthRemaining,
        )
        valueCache[key]?.let { return it }
        // Root choices stay complete. Only voluntary switches in simulated continuation
        // requests are narrowed; forced/pivot replacements retain every legal target.
        val allyActions = tree.actions(position, BattleSide.ALLY, FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT)
        val opponentActions = tree.actions(position, BattleSide.OPPONENT, FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT)
        if (allyActions.isEmpty() || opponentActions.isEmpty()) return evaluate(position.state) + position.recoilCredit
        var best = Double.NEGATIVE_INFINITY
        for (allyAction in allyActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(position, allyAction, opponentAction) ?: return null
                val value = projectedValue(child, depthRemaining - 1, worstResponse) ?: return null
                worstResponse = minOf(worstResponse, value)
            }
            best = maxOf(best, worstResponse)
            // The parent is minimizing responses and already has one worth upperBound. Once this
            // position can guarantee at least that value, its remaining ally actions cannot lower
            // the parent's minimum. This is only a lower bound, so never cache a cutoff result.
            if (best >= upperBound) return best
        }
        val result = if (best.isFinite()) best else evaluate(position.state) + position.recoilCredit
        if (!truncated) valueCache[key] = result
        return result
    }

    /**
     * Preserve the value of progress already realized this turn. A leaf-only horizon makes
     * "damage now, finish next turn" tie with "idle now, deal all damage next turn" whenever the
     * final board is the same; that gave near-full-HP Roost an unearned share of the root draw.
     * Intermediate material is cheap to read from the native state; the full positional evaluator
     * remains at the leaf. An ended battle has no later turn to discount.
     */
    private fun projectedValue(
        child: NativeSearchPosition,
        depthRemaining: Int,
        upperBound: Double,
    ): Double? {
        if (depthRemaining <= 0 || child.frame.ended) {
            return positionValue(child, depthRemaining, upperBound)
        }
        val immediateMaterial = LocalBoardMaterial.evaluate(child.state) + child.recoilCredit
        val remainingWeight = FUTURE_VALUE_WEIGHT
        val immediateWeight = 1.0 - remainingWeight
        // The parent's bound is expressed after this affine blend. Map it into the child's units
        // before pruning; otherwise a cutoff may contaminate another root action's exact score.
        val childBound = if (upperBound.isFinite()) {
            (upperBound - immediateWeight * immediateMaterial) / remainingWeight
        } else {
            upperBound
        }
        val continuation = positionValue(child, depthRemaining, childBound) ?: return null
        return immediateWeight * immediateMaterial + remainingWeight * continuation
    }

    private fun hpAdvantage(position: NativeSearchPosition): Double =
        position.frame.p1Team.sumOf { it.hp.toDouble() / it.maxHp } -
            position.frame.p2Team.sumOf { it.hp.toDouble() / it.maxHp } +
            // First-turn tempo measures progress, not the price of a move. Recoil is already
            // priced in the material value, so remove it completely from this extra tempo term.
            position.recoilCredit * 2.0

    private fun descend(
        position: NativeSearchPosition,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): NativeSearchPosition? {
        if (!timeAvailable()) return null
        val key = BranchKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            allyAction.actionId,
            opponentAction.actionId,
        )
        branchCache[key]?.let { return it }
        if (nodesVisited >= nodeLimit) {
            stop(NativeSearchTerminationReason.NODE_BUDGET)
            return null
        }
        nodesVisited++
        return tree.branch(position, allyAction, opponentAction).also { branchCache[key] = it }
    }

    private fun timeAvailable(): Boolean {
        if (truncated) return false
        if (shouldContinue()) return true
        stop(NativeSearchTerminationReason.DEADLINE)
        return false
    }

    private fun stop(reason: NativeSearchTerminationReason) {
        if (!truncated) {
            truncated = true
            terminationReason = reason
        }
    }

    private data class BranchKey(
        val rulesFingerprint: String,
        val hypothesisId: String,
        val randomSampleIndex: Int,
        val snapshotJson: String,
        val allyActionId: String,
        val opponentActionId: String,
    )

    private data class ValueKey(
        val rulesFingerprint: String,
        val hypothesisId: String,
        val randomSampleIndex: Int,
        val snapshotJson: String,
        val depthRemaining: Int,
    )

    private companion object {
        const val FUTURE_VOLUNTARY_SWITCH_TARGETS_PER_SLOT = 1
        const val DEFAULT_CACHE_ENTRY_LIMIT = 2_048
        const val FUTURE_VALUE_WEIGHT = 0.90
        const val ROOT_TEMPO_WEIGHT = 0.75
    }
}
