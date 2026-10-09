package jbro.cobblemon.mcc.betterai.search

import kotlin.math.abs
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition

/** One complete opponent world ready for product-native search. */
internal data class NativeProductWorldSearchInput(
    val key: NativeSearchWorldKey,
    val probability: Double,
    val definition: NativeBattleDefinition,
    val publicState: BattleStateView,
    val publicActionCatalog: BattlePublicActionCatalogView? = null,
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
    val responseMemory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
    val responseInformation: Double = 1.0,
    val allowSetupAttackExtension: Boolean = false,
    val excludeFutureAllyVoluntarySwitches: Boolean = false,
    /** See [LocalLookaheadBudget.opponentResponseLimit]. */
    val opponentResponseLimit: Int? = null,
    /** See [LocalLookaheadBudget.finalPlyAttacksOnly]. */
    val finalPlyAttacksOnly: Boolean = false,
    /** Canonical mechanics the live battle permits; null leaves native legality unfiltered. */
    val allowedMechanics: Set<String>? = null,
    val opponentThreatWeights: Map<java.util.UUID, Double> = emptyMap(),
    /** One total deterministic budget shared by every retained world. */
    val nodeLimit: Int,
    val deadlineNanos: Long,
    /** The clock [deadlineNanos] is measured on. */
    val nanoTime: () -> Long = System::nanoTime,
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
        require(responseInformation.isFinite() && responseInformation in 0.0..1.0)
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
 * Aggregates the posterior worlds' root values at one common depth.
 *
 * The clock, not the posterior, decides how many worlds a decision can afford: a 6v6 opening holds dozens
 * of sampled worlds, and searching them one after another to full depth spent the whole budget on the
 * first. So the search runs in two passes. The first completes depth one for as many worlds as the clock
 * allows, one sample of every hypothesis before any second sample and likelier worlds first; the second
 * deepens the completed worlds, splitting the remaining time between them. A world the clock never reached
 * or could not finish at depth one is left out and the others are renormalized. An execution failure, a
 * failed native root or a mismatched action set still invalidates the whole result: the remaining
 * probability mass is never renormalized around a broken world.
 */
internal class NativeProductWorldSearchAggregator(
    private val runWorld: (NativeProductSearchRequest) -> NativeProductSearchRun =
        NativeProductSearchRunner()::run,
) {
    fun search(request: NativeProductWorldSearchRequest): NativeProductWorldSearchResult {
        val orderedWorlds = request.worlds.sortedWith(
            compareBy<NativeProductWorldSearchInput> { it.key.randomSampleIndex }
                .thenByDescending { it.probability }
                .thenBy { it.key.hypothesisId }
                .thenBy { it.key.lineage },
        )
        var remainingNodeBudget = request.nodeLimit
        var nodesVisited = 0
        val completed = mutableListOf<CompletedWorld>()
        var skipped = false
        var firstSkipped: Pair<NativeProductWorldSearchInput, NativeProductSearchRun?>? = null

        fun run(world: NativeProductWorldSearchInput, maxDepth: Int, nodeLimit: Int, deadlineNanos: Long) = runWorld(
            NativeProductSearchRequest(
                definition = world.definition,
                publicState = world.publicState,
                publicActionCatalog = world.publicActionCatalog,
                rootSnapshot = world.rootSnapshot,
                productActions = request.productActions,
                world = world.key,
                maxDepth = maxDepth,
                responseMemory = request.responseMemory,
                responseInformation = request.responseInformation,
                allowSetupAttackExtension = request.allowSetupAttackExtension,
                excludeFutureAllyVoluntarySwitches = request.excludeFutureAllyVoluntarySwitches,
                opponentResponseLimit = request.opponentResponseLimit,
                finalPlyAttacksOnly = request.finalPlyAttacksOnly,
                allowedMechanics = request.allowedMechanics,
                opponentThreatWeights = request.opponentThreatWeights,
                nodeLimit = nodeLimit,
                deadlineNanos = deadlineNanos,
                evaluate = world.evaluate,
            ),
        )

        // Pass 1: depth one for as many worlds as the clock allows.
        for ((index, world) in orderedWorlds.withIndex()) {
            if (timeUp(request)) {
                skipped = true
                if (firstSkipped == null) firstSkipped = world to null
                break
            }
            val worldNodeLimit = (remainingNodeBudget / (orderedWorlds.size - index)).coerceAtLeast(1)
            val run = run(world, minOf(1, request.maxDepth), worldNodeLimit, request.deadlineNanos)
            val result = run.result
            val visited = result?.nodesVisited ?: 0
            nodesVisited += visited
            remainingNodeBudget = (remainingNodeBudget - visited).coerceAtLeast(0)
            if (run.status != NativeProductSearchRunStatus.COMPLETED &&
                run.status != NativeProductSearchRunStatus.DEADLINE_EXHAUSTED
            ) {
                return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, world, nodesVisited, run)
            }
            if (result == null || result.depthCompleted == 0) {
                // Out of time before depth one: this world and every later one are left out.
                skipped = true
                if (firstSkipped == null) firstSkipped = world to run
                break
            }
            val rootSnapshot = run.rootSnapshot
                ?: return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, world, nodesVisited, run)
            if (visited > worldNodeLimit) {
                return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, world, nodesVisited, run)
            }
            completed += CompletedWorld(world, result, rootSnapshot)
        }
        if (completed.isEmpty()) {
            val (world, run) = firstSkipped ?: (orderedWorlds.first() to null)
            return failure(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, world, nodesVisited, run)
        }

        // Pass 2: deepen the completed worlds, each with an equal share of the time and nodes left.
        if (request.maxDepth > 1) {
            for (index in completed.indices) {
                if (timeUp(request)) break
                val current = completed[index]
                if (current.result.depthCompleted >= request.maxDepth) continue
                val remainingWorlds = completed.size - index
                val now = request.nanoTime()
                val sliceDeadline = now + (request.deadlineNanos - now) / remainingWorlds
                val worldNodeLimit = (remainingNodeBudget / remainingWorlds).coerceAtLeast(1)
                val run = run(current.input, request.maxDepth, worldNodeLimit, sliceDeadline)
                val result = run.result
                val visited = result?.nodesVisited ?: 0
                nodesVisited += visited
                remainingNodeBudget = (remainingNodeBudget - visited).coerceAtLeast(0)
                if (run.status != NativeProductSearchRunStatus.COMPLETED &&
                    run.status != NativeProductSearchRunStatus.DEADLINE_EXHAUSTED
                ) {
                    return failure(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, current.input, nodesVisited, run)
                }
                val rootSnapshot = run.rootSnapshot
                if (result != null && rootSnapshot != null && result.depthCompleted > current.result.depthCompleted) {
                    completed[index] = CompletedWorld(current.input, result, rootSnapshot)
                }
            }
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
        // The deepest depth reached by worlds holding at least half the completed mass. Worlds short of it
        // are left out rather than mixing depths in one sum.
        val completedMass = completed.sumOf { it.input.probability }
        val commonDepth = (request.maxDepth downTo 1).firstOrNull { depth ->
            completed.filter { it.result.depthCompleted >= depth }.sumOf { it.input.probability } >=
                completedMass * MIN_DEEPENED_MASS_SHARE - MASS_EPSILON
        } ?: 1
        val included = completed.filter { it.result.depthCompleted >= commonDepth }
        val includedMass = included.sumOf { it.input.probability }
        val expectedActionIds = request.productActions.map(BattleActionCandidate::actionId)
        val valuesByWorld = included.map { completedWorld ->
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
                    world.probability / includedMass * values.getValue(action.actionId).value
                },
            )
        }
        val fullyCompleted = !skipped && included.size == completed.size && commonDepth == request.maxDepth &&
            included.all { completedWorld ->
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
            rootSnapshots = included.associate { it.input.key to it.rootSnapshot },
        )
    }

    private fun timeUp(request: NativeProductWorldSearchRequest): Boolean =
        request.nanoTime() - request.deadlineNanos >= 0L

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
                        "ambiguousProduct=${ambiguousProductActionIds.size},ambiguousNative=${ambiguousNativeActionIds.size}" +
                        idSample("unmatchedProductIds", unmatchedProductActionIds) +
                        idSample("unmatchedNativeIds", unmatchedNativeActionIds)
                }
                failed.failure != null -> failed.failure.javaClass.simpleName + ":" +
                    (failed.failure.message ?: "no message").take(240)
                else -> null
            }
        },
    )

    /** A few IDs are enough to name the mismatch without flooding the log. */
    private fun idSample(label: String, ids: Set<String>): String =
        if (ids.isEmpty()) "" else ",$label=" + ids.take(ID_SAMPLE_LIMIT).joinToString("|") +
            if (ids.size > ID_SAMPLE_LIMIT) "|..." else ""

    private data class CompletedWorld(
        val input: NativeProductWorldSearchInput,
        val result: NativeRecursiveSearchResult,
        val rootSnapshot: NativeProductRootSnapshot,
    )
}

private const val ID_SAMPLE_LIMIT = 6
private const val MIN_DEEPENED_MASS_SHARE = 0.5
private const val MASS_EPSILON = 1e-9
