package jbro.cobblemon.morebattlecontent.betterai.search

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
 * unlikely to finish. Only the fourth ply is gated: shallower tiers and the Boss's first three fully
 * evaluated turns retain their existing contract.
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
    ): LocalDepthAdmissionDecision {
        if (completedDepth >= requestedDepth || completedDepth < MINIMUM_GATED_DEPTH) {
            return LocalDepthAdmissionDecision.CONTINUE
        }
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
}
