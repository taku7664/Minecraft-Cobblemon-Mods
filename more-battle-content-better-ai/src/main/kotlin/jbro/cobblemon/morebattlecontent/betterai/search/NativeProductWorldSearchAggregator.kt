package jbro.cobblemon.morebattlecontent.betterai.search

import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition

/** One complete opponent world ready for product-native search. */
internal data class NativeProductWorldSearchInput(
    val key: NativeSearchWorldKey,
    val probability: Double,
    val definition: NativeBattleDefinition,
    val publicState: BattleStateView,
    val rootSnapshot: NativeProductRootSnapshot? = null,
    val evaluate: (BattleStateView) -> Double,
) {
    init {
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
    }
}

internal data class NativeProductWorldSearchRequest(
    val worlds: List<NativeProductWorldSearchInput>,
    val productActions: List<BattleActionCandidate>,
    val maxDepth: Int,
    val excludeFutureAllyVoluntarySwitches: Boolean = false,
    /** One total deterministic budget shared by every retained world. */
    val nodeLimit: Int,
    val deadlineNanos: Long,
) {
    init {
        require(worlds.isNotEmpty())
        require(worlds.map(NativeProductWorldSearchInput::key).distinct().size == worlds.size)
        require(abs(worlds.sumOf(NativeProductWorldSearchInput::probability) - 1.0) <= NORMALIZATION_EPSILON) {
            "Native product world probabilities must sum to one"
        }
        require(productActions.isNotEmpty())
        require(productActions.map(BattleActionCandidate::actionId).distinct().size == productActions.size)
        require(maxDepth > 0)
        require(nodeLimit >= worlds.size) {
            "The native product node budget must reserve at least one node per retained world"
        }
    }

    private companion object {
        const val NORMALIZATION_EPSILON = 1e-9
    }
}

internal enum class NativeProductWorldSearchStatus {
    COMPLETED,
    PARTIAL_DEPTH,
    WORLD_SEARCH_FAILED,
    NO_COMMON_COMPLETED_DEPTH,
    INCONSISTENT_ACTION_SET,
    INCONSISTENT_RULES_GENERATION,
}

internal data class NativeProductWorldSearchResult(
    val status: NativeProductWorldSearchStatus,
    val rootValues: List<NativeRootActionValue> = emptyList(),
    val depthCompleted: Int = 0,
    val nodesVisited: Int = 0,
    val failedWorldId: String? = null,
    val failedRunStatus: NativeProductSearchRunStatus? = null,
    val failedRunDetail: String? = null,
    val rootSnapshots: Map<NativeSearchWorldKey, NativeProductRootSnapshot> = emptyMap(),
) {
    init {
        require(depthCompleted >= 0)
        require(nodesVisited >= 0)
        require(rootValues.map { it.action.actionId }.distinct().size == rootValues.size)
        require(rootValues.all { it.value.isFinite() })
        require((status == NativeProductWorldSearchStatus.COMPLETED ||
            status == NativeProductWorldSearchStatus.PARTIAL_DEPTH) == rootValues.isNotEmpty())
        require((status == NativeProductWorldSearchStatus.COMPLETED ||
            status == NativeProductWorldSearchStatus.PARTIAL_DEPTH) == rootSnapshots.isNotEmpty())
    }

    val bestAction: BattleActionCandidate? = rootValues.maxByOrNull(NativeRootActionValue::value)?.action
}

/**
 * Aggregates only the deepest iteration completed by every posterior world.
 *
 * A missing world, a failed native root, or a mismatched action set invalidates the whole result;
 * the remaining probability mass is never silently renormalized around an execution failure.
 */
internal class NativeProductWorldSearchAggregator(
    private val runWorld: (NativeProductSearchRequest) -> NativeProductSearchRun =
        NativeProductSearchRunner()::run,
) {
    fun search(request: NativeProductWorldSearchRequest): NativeProductWorldSearchResult {
        val orderedWorlds = request.worlds.sortedWith(
            compareBy<NativeProductWorldSearchInput> { it.key.hypothesisId }
                .thenBy { it.key.randomSampleIndex }
                .thenBy { it.key.lineage },
        )
        var remainingNodeBudget = request.nodeLimit
        var nodesVisited = 0
        val completed = mutableListOf<CompletedWorld>()

        orderedWorlds.forEachIndexed { index, world ->
            val remainingWorlds = orderedWorlds.size - index
            val worldNodeLimit = (remainingNodeBudget / remainingWorlds).coerceAtLeast(1)
            val run = runWorld(
                NativeProductSearchRequest(
                    definition = world.definition,
                    publicState = world.publicState,
                    rootSnapshot = world.rootSnapshot,
                    productActions = request.productActions,
                    world = world.key,
                    maxDepth = request.maxDepth,
                    excludeFutureAllyVoluntarySwitches = request.excludeFutureAllyVoluntarySwitches,
                    nodeLimit = worldNodeLimit,
                    deadlineNanos = request.deadlineNanos,
                    evaluate = world.evaluate,
                ),
            )
            val result = run.result
            val visited = result?.nodesVisited ?: 0
            nodesVisited += visited
            remainingNodeBudget = (remainingNodeBudget - visited).coerceAtLeast(0)

            if (run.status != NativeProductSearchRunStatus.COMPLETED &&
                run.status != NativeProductSearchRunStatus.DEADLINE_EXHAUSTED
            ) {
                return failure(
                    NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                    world,
                    nodesVisited,
                    run,
                )
            }
            if (result == null || result.depthCompleted == 0) {
                return failure(
                    NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                    world,
                    nodesVisited,
                    run,
                )
            }
            val rootSnapshot = run.rootSnapshot ?: return failure(
                NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                world,
                nodesVisited,
                run,
            )
            if (visited > worldNodeLimit) {
                return failure(
                    NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                    world,
                    nodesVisited,
                    run,
                )
            }
            completed += CompletedWorld(world, result, rootSnapshot)
        }

        val firstRulesFingerprint = completed.first().rootSnapshot.rulesFingerprint
        completed.firstOrNull { it.rootSnapshot.rulesFingerprint != firstRulesFingerprint }?.let { mismatch ->
            return failure(
                NativeProductWorldSearchStatus.INCONSISTENT_RULES_GENERATION,
                mismatch.input,
                nodesVisited,
                null,
            )
        }
        val commonDepth = completed.minOf { it.result.depthCompleted }
        val expectedActionIds = request.productActions.map(BattleActionCandidate::actionId)
        val valuesByWorld = completed.map { completedWorld ->
            val world = completedWorld.input
            val result = completedWorld.result
            val iteration = result.completedIterations.single { it.depth == commonDepth }
            val actualIds = iteration.rootValues.map { it.action.actionId }
            if (actualIds.size != expectedActionIds.size || actualIds.toSet() != expectedActionIds.toSet()) {
                return failure(
                    NativeProductWorldSearchStatus.INCONSISTENT_ACTION_SET,
                    world,
                    nodesVisited,
                    null,
                )
            }
            world to iteration.rootValues.associateBy { it.action.actionId }
        }
        val aggregated = request.productActions.map { action ->
            NativeRootActionValue(
                action = action,
                value = valuesByWorld.sumOf { (world, values) ->
                    world.probability * values.getValue(action.actionId).value
                },
            )
        }
        val fullyCompleted = commonDepth == request.maxDepth && completed.all { completedWorld ->
            !completedWorld.result.truncated &&
                completedWorld.result.terminationReason == NativeSearchTerminationReason.COMPLETED
        }
        return NativeProductWorldSearchResult(
            status = if (fullyCompleted) {
                NativeProductWorldSearchStatus.COMPLETED
            } else {
                NativeProductWorldSearchStatus.PARTIAL_DEPTH
            },
            rootValues = aggregated,
            depthCompleted = commonDepth,
            nodesVisited = nodesVisited,
            rootSnapshots = completed.associate { it.input.key to it.rootSnapshot },
        )
    }

    private fun failure(
        status: NativeProductWorldSearchStatus,
        world: NativeProductWorldSearchInput,
        nodesVisited: Int,
        run: NativeProductSearchRun?,
    ) = NativeProductWorldSearchResult(
        status = status,
        nodesVisited = nodesVisited,
        failedWorldId = world.key.hypothesisId,
        failedRunStatus = run?.status,
        failedRunDetail = run?.let { failed ->
            when {
                failed.rootIssues.isNotEmpty() -> failed.rootIssues.joinToString(",") { issue ->
                    issue.code.name + (issue.battlePokemonId?.let { ":${it.toString().take(8)}" } ?: "")
                }
                failed.mapping != null -> with(failed.mapping) {
                    "unmatchedProduct=${unmatchedProductActionIds.size},unmatchedNative=${unmatchedNativeActionIds.size}," +
                        "ambiguousProduct=${ambiguousProductActionIds.size},ambiguousNative=${ambiguousNativeActionIds.size}"
                }
                failed.failure != null -> failed.failure.javaClass.simpleName + ":" +
                    (failed.failure.message ?: "no message").take(240)
                else -> null
            }
        },
    )

    private data class CompletedWorld(
        val input: NativeProductWorldSearchInput,
        val result: NativeRecursiveSearchResult,
        val rootSnapshot: NativeProductRootSnapshot,
    )
}
