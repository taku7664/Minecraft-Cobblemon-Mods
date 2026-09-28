package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.search.LocalCompletedDepthCost
import jbro.cobblemon.mcc.betterai.search.LocalDepthAdmissionDecision
import jbro.cobblemon.mcc.betterai.search.LocalDepthAdmissionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadDecisionSignature
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
    fun `a second turn whose root width makes it hopeless is skipped and a singles one is not`() {
        fun after(nodes: Int, pairs: Int) = LocalDepthAdmissionPolicy.afterCompletedDepth(
            completedDepth = 1,
            requestedDepth = 2,
            remainingMillis = 8_000,
            previousCost = null,
            currentCost = LocalCompletedDepthCost(elapsedMillis = 2_000, nodesVisited = nodes),
            previousSignature = null,
            currentSignature = null,
            rootPairs = pairs,
            nodeLimit = 400_000,
        )
        // A doubles turn: 150k nodes over 1,500 root pairs.
        assertEquals(LocalDepthAdmissionDecision.STOP_PREDICTED_COST, after(nodes = 150_000, pairs = 1_500))
        // A singles turn: 4k nodes over 81 root pairs.
        assertEquals(LocalDepthAdmissionDecision.CONTINUE, after(nodes = 4_000, pairs = 81))
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
