package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeCompletedSearchDepth
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRun
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchInput
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchRequest
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRecursiveSearchResult
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRootActionValue
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchTerminationReason
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeProductWorldSearchAggregatorTest {
    @Test
    fun `aggregates every complete world by posterior probability`() {
        val calls = mutableListOf<String>()
        val aggregator = NativeProductWorldSearchAggregator { request ->
            calls += request.world.hypothesisId
            when (request.world.hypothesisId) {
                "world-a" -> completed(request.productActions, 1, 10.0, 0.0)
                "world-b" -> completed(request.productActions, 1, 0.0, 8.0)
                else -> error("unexpected world")
            }
        }

        val result = aggregator.search(request())

        assertEquals(NativeProductWorldSearchStatus.COMPLETED, result.status)
        assertEquals(listOf("world-a", "world-b"), calls)
        assertEquals(1, result.depthCompleted)
        assertEquals(mapOf("action-a" to 2.5, "action-b" to 6.0), result.rootValues.associate {
            it.action.actionId to it.value
        })
        assertEquals("action-b", result.bestAction?.actionId)
    }

    @Test
    fun `uses the deepest depth completed by every world instead of mixing depths`() {
        val aggregator = NativeProductWorldSearchAggregator { request ->
            when (request.world.hypothesisId) {
                "world-a" -> completed(
                    request.productActions,
                    depth = 2,
                    depthOne = 10.0 to 0.0,
                    final = 100.0 to 0.0,
                )
                "world-b" -> partial(request.productActions, 0.0, 8.0)
                else -> error("unexpected world")
            }
        }

        val result = aggregator.search(request(maxDepth = 2))

        assertEquals(NativeProductWorldSearchStatus.PARTIAL_DEPTH, result.status)
        assertEquals(1, result.depthCompleted)
        assertEquals(mapOf("action-a" to 2.5, "action-b" to 6.0), result.rootValues.associate {
            it.action.actionId to it.value
        })
        assertEquals("action-b", result.bestAction?.actionId)
    }

    @Test
    fun `one world without a complete depth fails the whole posterior`() {
        val aggregator = NativeProductWorldSearchAggregator { request ->
            if (request.world.hypothesisId == "world-a") {
                completed(request.productActions, 1, 10.0, 0.0)
            } else {
                NativeProductSearchRun(
                    status = NativeProductSearchRunStatus.DEADLINE_EXHAUSTED,
                    result = result(request.productActions, emptyList(), 0, true, NativeSearchTerminationReason.DEADLINE),
                )
            }
        }

        val result = aggregator.search(request())

        assertEquals(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, result.status)
        assertTrue(result.rootValues.isEmpty())
        assertEquals("world-b", result.failedWorldId)
    }

    @Test
    fun `a missing root action in any world is an explicit failure`() {
        val aggregator = NativeProductWorldSearchAggregator { request ->
            val actions = if (request.world.hypothesisId == "world-a") {
                request.productActions
            } else {
                request.productActions.take(1)
            }
            completed(actions, 1, 10.0, 0.0)
        }

        val result = aggregator.search(request())

        assertEquals(NativeProductWorldSearchStatus.INCONSISTENT_ACTION_SET, result.status)
        assertTrue(result.rootValues.isEmpty())
        assertEquals("world-b", result.failedWorldId)
    }

    @Test
    fun `shares one total node budget across worlds`() {
        val limits = mutableListOf<Int>()
        val aggregator = NativeProductWorldSearchAggregator { request ->
            limits += request.nodeLimit
            completed(request.productActions, 1, 1.0, 0.0, nodesVisited = request.nodeLimit)
        }

        val result = aggregator.search(request(nodeLimit = 10))

        assertEquals(listOf(5, 5), limits)
        assertEquals(10, result.nodesVisited)
    }

    private fun request(
        maxDepth: Int = 1,
        nodeLimit: Int = 100,
    ) = NativeProductWorldSearchRequest(
        worlds = listOf(
            world("world-b", 0.75),
            world("world-a", 0.25),
        ),
        productActions = ACTIONS,
        maxDepth = maxDepth,
        nodeLimit = nodeLimit,
        deadlineNanos = Long.MAX_VALUE,
    )

    private fun world(id: String, probability: Double) = NativeProductWorldSearchInput(
        key = NativeSearchWorldKey(id, 0),
        probability = probability,
        definition = DEFINITION,
        publicState = STATE,
        evaluate = { 0.0 },
    )

    private fun completed(
        actions: List<BattleActionCandidate>,
        depth: Int,
        first: Double,
        second: Double,
        nodesVisited: Int = 1,
    ) = completed(actions, depth, first to second, first to second, nodesVisited)

    private fun completed(
        actions: List<BattleActionCandidate>,
        depth: Int,
        depthOne: Pair<Double, Double>,
        final: Pair<Double, Double>,
        nodesVisited: Int = 1,
    ): NativeProductSearchRun {
        val iterations = buildList {
            add(iteration(1, actions, depthOne))
            if (depth >= 2) add(iteration(2, actions, final))
        }
        return NativeProductSearchRun(
            status = NativeProductSearchRunStatus.COMPLETED,
            result = result(actions, iterations, depth, false, NativeSearchTerminationReason.COMPLETED, nodesVisited),
        )
    }

    private fun partial(
        actions: List<BattleActionCandidate>,
        first: Double,
        second: Double,
    ) = NativeProductSearchRun(
        status = NativeProductSearchRunStatus.DEADLINE_EXHAUSTED,
        result = result(
            actions,
            listOf(iteration(1, actions, first to second)),
            1,
            true,
            NativeSearchTerminationReason.DEADLINE,
        ),
    )

    private fun iteration(
        depth: Int,
        actions: List<BattleActionCandidate>,
        values: Pair<Double, Double>,
    ) = NativeCompletedSearchDepth(
        depth,
        actions.zip(listOf(values.first, values.second)).map { (action, value) ->
            NativeRootActionValue(action, value)
        },
    )

    private fun result(
        actions: List<BattleActionCandidate>,
        iterations: List<NativeCompletedSearchDepth>,
        depth: Int,
        truncated: Boolean,
        reason: NativeSearchTerminationReason,
        nodesVisited: Int = 1,
    ) = NativeRecursiveSearchResult(
        rootValues = iterations.lastOrNull()?.rootValues.orEmpty().ifEmpty {
            actions.take(0).map { NativeRootActionValue(it, 0.0) }
        },
        completedIterations = iterations,
        nodesVisited = nodesVisited,
        depthCompleted = depth,
        truncated = truncated,
        terminationReason = reason,
    )

    private companion object {
        val ACTIONS = listOf(
            BattleActionCandidate("action-a", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "a"),
            BattleActionCandidate("action-b", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = "b"),
        )
        val ALLY = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val STATE = BattleStateView(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            BattleFormat.SINGLE,
            1,
            listOf(pokemon(ALLY, BattleSide.ALLY), pokemon(OPPONENT, BattleSide.OPPONENT)),
            BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(),
            emptyList(),
        )
        val DEFINITION = NativeBattleDefinition(
            "cobblemonsingles",
            listOf(1, 2, 3, 4),
            listOf(set("ally", ALLY, "a")),
            listOf(set("opponent", OPPONENT, "b")),
        )

        fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
            id, side, 0, "cobblemon:mew", null, 50, 1.0, null,
            emptyMap(), emptySet(), null, null, false,
        )

        fun set(name: String, id: UUID, move: String) = NativePokemonSet(
            name = name,
            species = "mew",
            moves = listOf(move),
            ability = "synchronize",
            uuid = id.toString(),
        )
    }
}
