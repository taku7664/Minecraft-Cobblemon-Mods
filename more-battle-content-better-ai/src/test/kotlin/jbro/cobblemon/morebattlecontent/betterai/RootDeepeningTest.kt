package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootDeepeningTest {
    @Test
    fun `priority uses observed recursive scores only after complete first depth`() {
        val calls = mutableListOf<Pair<String, Int>>()
        val result = RootDeepeningAllocator.run(listOf("b", "a"), RootDeepeningPolicy.SCORE_PRIORITY, 3) { id, depth, _ ->
            calls += id to depth
            RootDeepeningReading(if (id == "b") 10.0 else 1.0, 1)
        }
        assertEquals(listOf("a" to 1, "b" to 1, "b" to 2), calls)
        assertEquals("b", result.chosen)
        assertEquals(mapOf("a" to 1, "b" to 2), result.depths)
        assertFalse(result.targetDepthComplete)
    }

    @Test
    fun `incomplete first depth cannot recommend and interrupted work remains charged`() {
        val result = RootDeepeningAllocator.run(listOf("a", "b"), RootDeepeningPolicy.SCORE_PRIORITY, 5) { id, _, remaining ->
            if (id == "a") RootDeepeningReading(1.0, 1) else RootDeepeningReading(null, remaining)
        }
        assertNull(result.chosen)
        assertEquals(5, result.nodes)
        assertEquals(mapOf("a" to 1), result.depths)
        assertNull(result.attempts.last().reading.executionProbability)
        assertNull(result.attempts.last().reading.worstResponseHpRetention)
        assertNull(result.attempts.last().reading.publicResponseIncomplete)
    }

    @Test
    fun `partial deeper result cannot replace a completed shallow score`() {
        val result = RootDeepeningAllocator.run(listOf("a"), RootDeepeningPolicy.CANONICAL, 5) { _, depth, remaining ->
            if (depth == 1) RootDeepeningReading(7.0, 1) else RootDeepeningReading(null, remaining)
        }
        assertEquals(7.0, result.scores.getValue("a"))
        assertEquals(1, result.depths.getValue("a"))
        assertEquals(5, result.nodes)
    }

    @Test
    fun `priority can reveal a trap sooner but can also miss a delayed payoff`() {
        fun choose(policy: RootDeepeningPolicy, values: Map<String, List<Double>>) =
            RootDeepeningAllocator.run(values.keys.toList(), policy, 4, RootDepthAcceptance.LATEST_COMPLETED) { id, depth, _ ->
                RootDeepeningReading(values.getValue(id)[depth - 1], 1)
            }.chosen
        val trap = mapOf("a" to listOf(0.0, 0.0), "b" to listOf(10.0, -100.0), "c" to listOf(9.0, 9.0))
        assertEquals("b", choose(RootDeepeningPolicy.CANONICAL, trap))
        assertEquals("c", choose(RootDeepeningPolicy.SCORE_PRIORITY, trap))
        val payoff = mapOf("a" to listOf(9.0, 100.0), "b" to listOf(10.0, 11.0), "c" to listOf(0.0, 0.0))
        assertEquals("a", choose(RootDeepeningPolicy.CANONICAL, payoff))
        assertEquals("b", choose(RootDeepeningPolicy.SCORE_PRIORITY, payoff))
    }

    @Test
    fun `complete target depth agrees regardless of schedule and does not repeat work`() {
        for (policy in RootDeepeningPolicy.entries) {
            val result = RootDeepeningAllocator.run(listOf("b", "a"), policy, 100) { id, depth, _ ->
                RootDeepeningReading(if (depth == 2 && id == "a") 20.0 else 1.0, 1)
            }
            assertEquals("a", result.chosen)
            assertEquals(4, result.nodes)
            assertEquals(4, result.attempts.size)
            assertTrue(result.targetDepthComplete)
        }
    }

    @Test
    fun `acceptance changes selection not work and defers useful partial warnings`() {
        val values = mapOf("a" to listOf(0.0, 0.0), "b" to listOf(10.0, -100.0), "c" to listOf(9.0, 9.0))
        fun run(acceptance: RootDepthAcceptance, budget: Int) = RootDeepeningAllocator.run(
            values.keys.toList(), RootDeepeningPolicy.SCORE_PRIORITY, budget, acceptance) { id, depth, _ ->
            RootDeepeningReading(values.getValue(id)[depth - 1], 1)
        }
        for (budget in 1..6) {
            val common = run(RootDepthAcceptance.COMMON_DEPTH, budget)
            val latest = run(RootDepthAcceptance.LATEST_COMPLETED, budget)
            assertEquals(latest.attempts, common.attempts)
            assertEquals(latest.nodes, common.nodes)
            assertEquals(latest.scores, common.scores)
            assertEquals(latest.depths, common.depths)
            if (budget < 3) {
                assertNull(common.chosen)
                assertNull(common.selectionDepth)
                assertTrue(common.selectionScores.isEmpty())
            } else if (budget < 6) {
                assertEquals("b", common.chosen)
                assertEquals(1, common.selectionDepth)
                assertEquals(values.mapValues { it.value.first() }, common.selectionScores)
            } else {
                assertEquals("c", common.chosen)
                assertEquals(2, common.selectionDepth)
                assertEquals(common.scores, common.selectionScores)
            }
        }
        // Deliberate tradeoff: the common snapshot cannot use an early valid warning about b.
        assertEquals("c", run(RootDepthAcceptance.LATEST_COMPLETED, 4).chosen)
        assertNull(run(RootDepthAcceptance.LATEST_COMPLETED, 4).selectionDepth)
    }

    @Test
    fun `invalid evaluator costs or scores fail instead of corrupting budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootDeepeningAllocator.run(listOf("a"), RootDeepeningPolicy.CANONICAL, 5) { _, _, _ -> RootDeepeningReading(1.0, 6) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootDeepeningAllocator.run(listOf("a"), RootDeepeningPolicy.CANONICAL, 5) { _, _, _ -> RootDeepeningReading(Double.NaN, 1) }
        }
    }
}
