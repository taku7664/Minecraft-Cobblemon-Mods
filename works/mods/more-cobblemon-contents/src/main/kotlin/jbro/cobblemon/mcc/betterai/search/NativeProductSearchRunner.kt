package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeRootActionMapping
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRuntimeService
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownSearchTree

internal data class NativeProductSearchRequest(
    val definition: NativeBattleDefinition,
    val publicState: BattleStateView,
    val publicActionCatalog: BattlePublicActionCatalogView? = null,
    val rootSnapshot: NativeProductRootSnapshot? = null,
    val productActions: List<BattleActionCandidate>,
    val world: NativeSearchWorldKey,
    val maxDepth: Int,
    val responseMemory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
    val responseInformation: Double = 1.0,
    val allowSetupAttackExtension: Boolean = false,
    val excludeFutureAllyVoluntarySwitches: Boolean = false,
    val allowedMechanics: Set<String>? = null,
    val opponentThreatWeights: Map<java.util.UUID, Double> = emptyMap(),
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
        require(responseInformation.isFinite() && responseInformation in 0.0..1.0)
        require(nodeLimit > 0)
    }
}

/** A reusable native root is valid only under the immutable rules generation that created it. */
internal data class NativeProductRootSnapshot(
    val rulesFingerprint: String,
    val frame: NativeBattleFrame,
    val publicTurnOffset: Int = 0,
) {
    init {
        require(rulesFingerprint.isNotBlank())
        require(frame.snapshotJson.isNotBlank())
        require(publicTurnOffset in 0..1)
    }
}

internal enum class NativeProductSearchRunStatus {
    COMPLETED,
    DEADLINE_EXHAUSTED,
    RUNTIME_UNAVAILABLE,
    ROOT_STATE_INCONSISTENT,
    ROOT_ACTION_MAPPING_INCOMPLETE,
    RULES_GENERATION_MISMATCH,
    NATIVE_EXECUTION_FAILURE,
}

internal data class NativeProductSearchRun(
    val status: NativeProductSearchRunStatus,
    val result: NativeRecursiveSearchResult? = null,
    val mapping: NativeRootActionMapping? = null,
    val failure: Throwable? = null,
    val rootIssues: List<NativeBattleRootIssue> = emptyList(),
    val rootSnapshot: NativeProductRootSnapshot? = null,
)

internal sealed interface NativeLeasedProductSearch

internal data class NativeLeasedProductSearchAttempt(
    val attempt: NativeProductSearchAttempt,
    val rootSnapshot: NativeProductRootSnapshot,
) : NativeLeasedProductSearch

internal data class NativeLeasedInvalidRoot(
    val issues: List<NativeBattleRootIssue>,
) : NativeLeasedProductSearch

private data object NativeLeasedRulesGenerationMismatch : NativeLeasedProductSearch

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
                val suppliedRoot = request.rootSnapshot
                if (suppliedRoot != null && suppliedRoot.rulesFingerprint != worker.rulesFingerprint) {
                    return@lease NativeLeasedRulesGenerationMismatch
                }
                val root = suppliedRoot?.frame ?: worker.createBattle(request.definition)
                val publicTurnOffset = suppliedRoot?.publicTurnOffset
                    ?: if (request.publicState.turn == 0 && root.turn == 1) 1 else 0
                val rootIssues = NativeBattleRootValidator.validate(
                    request.definition, root, request.publicState, publicTurnOffset)
                if (rootIssues.isNotEmpty()) return@lease NativeLeasedInvalidRoot(rootIssues)
                val tree = NativeShowdownSearchTree(worker, root, request.publicState,
                    publicTurnOffset, request.publicActionCatalog, request.allowedMechanics)
                NativeLeasedProductSearchAttempt(
                    attempt = NativeRecursiveSearch(
                        tree = tree,
                        world = request.world,
                        evaluate = request.evaluate,
                        nodeLimit = request.nodeLimit,
                        responseMemory = request.responseMemory,
                        responseInformation = request.responseInformation,
                        allowSetupAttackExtension = request.allowSetupAttackExtension,
                        excludeFutureAllyVoluntarySwitches = request.excludeFutureAllyVoluntarySwitches,
                        shouldContinue = { !deadlineReached(request.deadlineNanos) },
                        opponentThreatWeights = request.opponentThreatWeights,
                        tolerateExtraNativeRootActions = request.definition.situation != null,
                    ).evaluateProduct(request.productActions, request.maxDepth),
                    rootSnapshot = suppliedRoot ?: NativeProductRootSnapshot(worker.rulesFingerprint, root, publicTurnOffset),
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
        if (leased === NativeLeasedRulesGenerationMismatch) {
            return NativeProductSearchRun(NativeProductSearchRunStatus.RULES_GENERATION_MISMATCH)
        }
        leased as NativeLeasedProductSearchAttempt
        val attempt = leased.attempt
        if (attempt.result == null) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.ROOT_ACTION_MAPPING_INCOMPLETE,
                mapping = attempt.mapping,
                rootSnapshot = leased.rootSnapshot,
            )
        }
        if (attempt.result.terminationReason == NativeSearchTerminationReason.DEADLINE) {
            return NativeProductSearchRun(
                status = NativeProductSearchRunStatus.DEADLINE_EXHAUSTED,
                result = attempt.result,
                mapping = attempt.mapping,
                rootSnapshot = leased.rootSnapshot,
            )
        }
        return NativeProductSearchRun(
            status = NativeProductSearchRunStatus.COMPLETED,
            result = attempt.result,
            mapping = attempt.mapping,
            rootSnapshot = leased.rootSnapshot,
        )
    }

    private fun deadlineReached(deadlineNanos: Long): Boolean = nanoTime() - deadlineNanos >= 0L
}
