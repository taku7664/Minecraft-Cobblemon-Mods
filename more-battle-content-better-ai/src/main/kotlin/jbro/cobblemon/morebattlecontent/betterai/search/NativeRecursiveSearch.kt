package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeSearchPosition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree

/** Identifies one public opponent hypothesis evaluated with one common random sample. */
internal data class NativeSearchWorldKey(
    val hypothesisId: String,
    val randomSampleIndex: Int,
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

internal data class NativeRecursiveSearchResult(
    val rootValues: List<NativeRootActionValue>,
    val nodesVisited: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val terminationReason: NativeSearchTerminationReason,
) {
    val bestAction: BattleActionCandidate? = rootValues.maxByOrNull(NativeRootActionValue::value)?.action
}

/** Iterative-deepening minimax whose state transitions come only from native Showdown snapshots. */
internal class NativeRecursiveSearch(
    private val tree: NativeShowdownSearchTree,
    private val world: NativeSearchWorldKey,
    private val evaluate: (BattleStateView) -> Double,
    private val nodeLimit: Int,
    private val shouldContinue: () -> Boolean = { true },
) {
    private var nodesVisited = 0
    private var truncated = false
    private var terminationReason = NativeSearchTerminationReason.COMPLETED
    private val branchCache = HashMap<BranchKey, NativeSearchPosition>()
    private val valueCache = HashMap<ValueKey, Double>()

    init {
        require(nodeLimit > 0)
    }

    fun evaluate(maxDepth: Int): NativeRecursiveSearchResult {
        require(maxDepth > 0)
        val rootActions = tree.actions(tree.root, BattleSide.ALLY)
        var accepted = emptyList<NativeRootActionValue>()
        var completedDepth = 0
        for (depth in 1..maxDepth) {
            val iteration = evaluateRootDepth(rootActions, depth)
            if (truncated || iteration == null) break
            accepted = iteration
            completedDepth = depth
        }
        return NativeRecursiveSearchResult(
            rootValues = accepted,
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
        val values = mutableListOf<NativeRootActionValue>()
        for (allyAction in rootActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(tree.root, allyAction, opponentAction, depth) ?: return null
                val value = positionValue(child, depth - 1) ?: return null
                worstResponse = minOf(worstResponse, value)
            }
            values += NativeRootActionValue(allyAction, worstResponse)
        }
        return values
    }

    private fun positionValue(position: NativeSearchPosition, depthRemaining: Int): Double? {
        if (!timeAvailable()) return null
        if (depthRemaining <= 0 || position.frame.ended) return evaluate(position.state)
        val key = ValueKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            depthRemaining,
        )
        valueCache[key]?.let { return it }
        val allyActions = tree.actions(position, BattleSide.ALLY)
        val opponentActions = tree.actions(position, BattleSide.OPPONENT)
        if (allyActions.isEmpty() || opponentActions.isEmpty()) return evaluate(position.state)
        var best = Double.NEGATIVE_INFINITY
        for (allyAction in allyActions) {
            var worstResponse = Double.POSITIVE_INFINITY
            for (opponentAction in opponentActions) {
                val child = descend(position, allyAction, opponentAction, depthRemaining) ?: return null
                val value = positionValue(child, depthRemaining - 1) ?: return null
                worstResponse = minOf(worstResponse, value)
            }
            best = maxOf(best, worstResponse)
        }
        val result = if (best.isFinite()) best else evaluate(position.state)
        if (!truncated) valueCache[key] = result
        return result
    }

    private fun descend(
        position: NativeSearchPosition,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
        depthRemaining: Int,
    ): NativeSearchPosition? {
        if (!timeAvailable()) return null
        if (nodesVisited >= nodeLimit) {
            stop(NativeSearchTerminationReason.NODE_BUDGET)
            return null
        }
        val key = BranchKey(
            tree.rulesFingerprint,
            world.hypothesisId,
            world.randomSampleIndex,
            position.frame.snapshotJson,
            allyAction.actionId,
            opponentAction.actionId,
            depthRemaining,
        )
        nodesVisited++
        return branchCache.getOrPut(key) {
            tree.branch(position, allyAction, opponentAction)
        }
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
        val depthRemaining: Int,
    )

    private data class ValueKey(
        val rulesFingerprint: String,
        val hypothesisId: String,
        val randomSampleIndex: Int,
        val snapshotJson: String,
        val depthRemaining: Int,
    )
}
