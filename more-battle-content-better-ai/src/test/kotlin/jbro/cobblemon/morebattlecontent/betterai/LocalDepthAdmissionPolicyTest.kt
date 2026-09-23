package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.search.LocalCompletedDepthCost
import jbro.cobblemon.morebattlecontent.betterai.search.LocalDepthAdmissionDecision
import jbro.cobblemon.morebattlecontent.betterai.search.LocalDepthAdmissionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadDecisionSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalDepthAdmissionPolicyTest {
    @Test
    fun `a predicted depth that cannot finish is never started`() {
        val decision = LocalDepthAdmissionPolicy.afterCompletedDepth(
            completedDepth = 3,
            requestedDepth = 4,
            remainingMillis = 800,
            previousCost = LocalCompletedDepthCost(elapsedMillis = 200, nodesVisited = 20_000),
            currentCost = LocalCompletedDepthCost(elapsedMillis = 600, nodesVisited = 80_000),
            previousSignature = null,
            currentSignature = null,
        )

        assertEquals(LocalDepthAdmissionDecision.STOP_PREDICTED_COST, decision)
    }

    @Test
    fun `a cheap next depth is still admitted`() {
        val decision = LocalDepthAdmissionPolicy.afterCompletedDepth(
            completedDepth = 3,
            requestedDepth = 4,
            remainingMillis = 500,
            previousCost = LocalCompletedDepthCost(elapsedMillis = 20, nodesVisited = 10_000),
            currentCost = LocalCompletedDepthCost(elapsedMillis = 50, nodesVisited = 20_000),
            previousSignature = null,
            currentSignature = null,
        )

        assertEquals(LocalDepthAdmissionDecision.CONTINUE, decision)
    }

    @Test
    fun `the same production decision at depths two and three stops a boss search`() {
        val stable = LocalLookaheadDecisionSignature(
            topActionId = "shadow_ball",
            selectedActionId = "moonblast",
            shortlistSize = 2,
        )

        val decision = LocalDepthAdmissionPolicy.afterCompletedDepth(
            completedDepth = 3,
            requestedDepth = 4,
            remainingMillis = 1_000,
            previousCost = LocalCompletedDepthCost(elapsedMillis = 100, nodesVisited = 10_000),
            currentCost = LocalCompletedDepthCost(elapsedMillis = 200, nodesVisited = 20_000),
            previousSignature = stable,
            currentSignature = stable,
        )

        assertEquals(LocalDepthAdmissionDecision.STOP_STABLE_DECISION, decision)
    }

    @Test
    fun `stability never shortens searches below three completed depths`() {
        val stable = LocalLookaheadDecisionSignature("best", "best", 1)

        val decision = LocalDepthAdmissionPolicy.afterCompletedDepth(
            completedDepth = 2,
            requestedDepth = 4,
            remainingMillis = 1,
            previousCost = LocalCompletedDepthCost(elapsedMillis = 100, nodesVisited = 10_000),
            currentCost = LocalCompletedDepthCost(elapsedMillis = 200, nodesVisited = 20_000),
            previousSignature = stable,
            currentSignature = stable,
        )

        assertEquals(LocalDepthAdmissionDecision.CONTINUE, decision)
    }
}
