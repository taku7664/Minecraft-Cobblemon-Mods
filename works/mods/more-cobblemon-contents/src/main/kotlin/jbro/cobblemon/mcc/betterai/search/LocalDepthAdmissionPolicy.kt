package jbro.cobblemon.mcc.betterai.search

import kotlin.math.ceil

internal data class LocalCompletedDepthCost(
    val elapsedMillis: Long,
    val nodesVisited: Int,
)

internal data class LocalLookaheadDecisionSignature(
    val topActionId: String,
    val selectedActionId: String,
    val shortlistSize: Int,
)

internal enum class LocalDepthAdmissionDecision {
    CONTINUE,
    STOP_PREDICTED_COST,
    STOP_STABLE_DECISION,
}

internal enum class LocalLookaheadTerminationReason {
    COMPLETED,
    PUBLIC_DATA_INCOMPLETE,
    TIME_BUDGET,
    NODE_BUDGET,
    PREDICTED_NEXT_DEPTH_COST,
    STABLE_DECISION,
    ROOT_VALIDATION_INCOMPLETE,
}

/**
 * Prevents iterative deepening from spending the rest of a decision on an iteration that is very
 * unlikely to finish. A depth that does not finish is discarded, so starting one only costs time.
 *
 * - Any depth: the next one is skipped when its node count is hopeless. One more turn repeats the
 *   finished depth's search under every root (own action, response) pair it projected, so its nodes are
 *   about this depth's times that width. A doubles turn has thousands of pairs and never finishes the
 *   second turn; a singles turn has tens and does. The gate stops only at [HOPELESS_FACTOR] times the
 *   node limit, far from either.
 * - From the fourth ply: also by the time the growth between the last two depths predicts.
 */
internal object LocalDepthAdmissionPolicy {
    fun afterCompletedDepth(
        completedDepth: Int,
        requestedDepth: Int,
        remainingMillis: Long,
        previousCost: LocalCompletedDepthCost?,
        currentCost: LocalCompletedDepthCost,
        previousSignature: LocalLookaheadDecisionSignature?,
        currentSignature: LocalLookaheadDecisionSignature?,
        /** Root (own action, response) pairs the finished depth projected; null when unknown. */
        rootPairs: Int? = null,
        /** The node limit the next depth would run under. */
        nodeLimit: Int? = null,
    ): LocalDepthAdmissionDecision {
        if (completedDepth >= requestedDepth) return LocalDepthAdmissionDecision.CONTINUE
        if (rootPairs != null && nodeLimit != null && rootPairs > 1 &&
            currentCost.nodesVisited.toDouble() * rootPairs > HOPELESS_FACTOR * nodeLimit
        ) {
            return LocalDepthAdmissionDecision.STOP_PREDICTED_COST
        }
        if (completedDepth < MINIMUM_GATED_DEPTH) return LocalDepthAdmissionDecision.CONTINUE
        if (previousSignature != null && currentSignature != null && previousSignature == currentSignature) {
            return LocalDepthAdmissionDecision.STOP_STABLE_DECISION
        }
        val predictedMillis = predictedNextDepthMillis(previousCost, currentCost) ?: return LocalDepthAdmissionDecision.CONTINUE
        return if (remainingMillis < predictedMillis + ADMISSION_MARGIN_MILLIS) {
            LocalDepthAdmissionDecision.STOP_PREDICTED_COST
        } else {
            LocalDepthAdmissionDecision.CONTINUE
        }
    }

    private fun predictedNextDepthMillis(
        previousCost: LocalCompletedDepthCost?,
        currentCost: LocalCompletedDepthCost,
    ): Long? {
        if (currentCost.elapsedMillis <= 0L || currentCost.nodesVisited <= 0) return null
        val growth = if (previousCost == null || previousCost.nodesVisited <= 0) {
            DEFAULT_DEPTH_GROWTH
        } else {
            (currentCost.nodesVisited.toDouble() / previousCost.nodesVisited.toDouble())
                .coerceIn(MINIMUM_DEPTH_GROWTH, MAXIMUM_DEPTH_GROWTH)
        }
        return ceil(currentCost.elapsedMillis * growth).toLong().coerceAtLeast(MINIMUM_PREDICTION_MILLIS)
    }

    private const val MINIMUM_GATED_DEPTH = 3
    private const val MINIMUM_DEPTH_GROWTH = 1.25
    private const val MAXIMUM_DEPTH_GROWTH = 4.0
    private const val DEFAULT_DEPTH_GROWTH = 2.0
    private const val MINIMUM_PREDICTION_MILLIS = 1L
    private const val ADMISSION_MARGIN_MILLIS = 20L
    const val HOPELESS_FACTOR = 4.0
}
