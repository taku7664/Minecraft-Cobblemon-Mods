package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRootActionMapping
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRuntimeService
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree

internal data class NativeProductSearchRequest(
    val definition: NativeBattleDefinition,
    val publicState: BattleStateView,
    val productActions: List<BattleActionCandidate>,
    val world: NativeSearchWorldKey,
    val maxDepth: Int,
    val nodeLimit: Int,
    val deadlineNanos: Long,
    val evaluate: (BattleStateView) -> Double,
) {
    init {
        require(productActions.isNotEmpty())
        require(productActions.map(BattleActionCandidate::actionId).distinct().size == productActions.size) {
            "Product action ids must be unique"
        }
        require(maxDepth > 0)
        require(nodeLimit > 0)
    }
}

internal enum class NativeProductSearchRunStatus {
    COMPLETED,
    DEADLINE_EXHAUSTED,
    RUNTIME_UNAVAILABLE,
    ROOT_STATE_INCONSISTENT,
    ROOT_ACTION_MAPPING_INCOMPLETE,
    NATIVE_EXECUTION_FAILURE,
}

internal data class NativeProductSearchRun(
    val status: NativeProductSearchRunStatus,
    val result: NativeRecursiveSearchResult? = null,
    val mapping: NativeRootActionMapping? = null,
    val failure: Throwable? = null,
    val rootIssues: List<NativeBattleRootIssue> = emptyList(),
)

internal sealed interface NativeLeasedProductSearch

internal data class NativeLeasedProductSearchAttempt(
    val attempt: NativeProductSearchAttempt,
) : NativeLeasedProductSearch

internal data class NativeLeasedInvalidRoot(
    val issues: List<NativeBattleRootIssue>,
) : NativeLeasedProductSearch

private typealias NativeProductSearchLease = (
    deadlineNanos: Long,
    action: (NativeBranchWorker) -> NativeLeasedProductSearch,
) -> NativeLeasedProductSearch?

/**
 * Runtime boundary between product candidates and the native Showdown search.
 *
 * A failed lease, root creation, or semantic action match is returned explicitly. This boundary
 * never substitutes the legacy handmade projector for a failed native search.
 */
internal class NativeProductSearchRunner(
    private val nanoTime: () -> Long = System::nanoTime,
    private val lease: NativeProductSearchLease = { deadlineNanos, action ->
        NativeShowdownRuntimeService.withWorker(deadlineNanos, action)
    },
) {
    fun run(request: NativeProductSearchRequest): NativeProductSearchRun {
        if (deadlineReached(request.deadlineNanos)) {
            return NativeProductSearchRun(NativeProductSearchRunStatus.DEADLINE_EXHAUSTED)
        }

        val leased = try {
            lease(request.deadlineNanos) { worker ->
                val root = worker.createBattle(request.definition)
                val rootIssues = NativeBattleRootValidator.validate(request.definition, root, request.publicState)
                if (rootIssues.isNotEmpty()) return@lease NativeLeasedInvalidRoot(rootIssues)
                val tree = NativeShowdownSearchTree(worker, root, request.publicState)
                NativeLeasedProductSearchAttempt(
                    NativeRecursiveSearch(
                        tree = tree,
                        world = request.world,
                        evaluate = request.evaluate,
                        nodeLimit = request.nodeLimit,
                        shouldContinue = { !deadlineReached(request.deadlineNanos) },
                    ).evaluateProduct(request.productActions, request.maxDepth),
                )
            }
        } catch (failure: Exception) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE,
                failure = failure,
            )
        } catch (failure: LinkageError) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.NATIVE_EXECUTION_FAILURE,
                failure = failure,
            )
        }

        if (leased == null) {
            val status = if (deadlineReached(request.deadlineNanos)) {
                NativeProductSearchRunStatus.DEADLINE_EXHAUSTED
            } else {
                NativeProductSearchRunStatus.RUNTIME_UNAVAILABLE
            }
            return NativeProductSearchRun(status)
        }
        if (leased is NativeLeasedInvalidRoot) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.ROOT_STATE_INCONSISTENT,
                rootIssues = leased.issues,
            )
        }
        val attempt = (leased as NativeLeasedProductSearchAttempt).attempt
        if (!attempt.mapping.complete || attempt.result == null) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.ROOT_ACTION_MAPPING_INCOMPLETE,
                mapping = attempt.mapping,
            )
        }
        if (attempt.result.terminationReason == NativeSearchTerminationReason.DEADLINE) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.DEADLINE_EXHAUSTED,
                result = attempt.result,
                mapping = attempt.mapping,
            )
        }
        return NativeProductSearchRun(
            status = NativeProductSearchRunStatus.COMPLETED,
            result = attempt.result,
            mapping = attempt.mapping,
        )
    }

    private fun deadlineReached(deadlineNanos: Long): Boolean = nanoTime() - deadlineNanos >= 0L
}
