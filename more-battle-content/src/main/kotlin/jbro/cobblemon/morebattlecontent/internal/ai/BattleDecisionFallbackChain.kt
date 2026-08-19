package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainDefaults
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecision
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidationStatus
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionValidator

/** Runs an untrusted Brain call off-thread and resolves it exactly once before the shared deadline. */
internal class BattleBrainDecisionCoordinator(
    private val scheduler: ScheduledExecutorService,
    private val brainExecutor: Executor = ForkJoinPool.commonPool(),
    private val maximumDecisionMillis: Long = BattleBrainDefaults.DECISION_TIMEOUT_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maximumDecisionMillis in 1..BattleBrainDefaults.DECISION_TIMEOUT_MILLIS)
    }

    fun decide(
        endpoint: BattleBrainEndpoint,
        context: BattleDecisionContext,
    ): CompletionStage<BattleBrainAttempt> {
        val result = CompletableFuture<BattleBrainAttempt>()
        val pendingDecision = AtomicReference<CompletableFuture<BattleDecision>?>()
        val remainingMillis = (context.deadlineEpochMillis - nowEpochMillis())
            .coerceIn(0L, maximumDecisionMillis)
        if (remainingMillis == 0L) {
            result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.DEADLINE_EXPIRED))
            return result
        }

        val timeout = scheduler.schedule(
            {
                pendingDecision.get()?.cancel(true)
                result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.TIMEOUT))
            },
            remainingMillis,
            TimeUnit.MILLISECONDS,
        )
        try {
            brainExecutor.execute {
                val decisionStage = try {
                    endpoint.brain.decide(endpoint.session(), context)
                } catch (_: Exception) {
                    if (result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE))) {
                        timeout.cancel(false)
                    }
                    return@execute
                }
                val decisionFuture = decisionStage.toCompletableFuture()
                pendingDecision.set(decisionFuture)
                if (result.isDone) {
                    decisionFuture.cancel(true)
                    return@execute
                }
                decisionFuture.whenComplete { decision, throwable ->
                    if (result.isDone) return@whenComplete
                    val attempt = when {
                        throwable != null || decision == null ->
                            BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE)

                        else -> validate(context, decision)
                    }
                    if (result.complete(attempt)) timeout.cancel(false)
                }
            }
        } catch (_: Exception) {
            timeout.cancel(false)
            result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE))
        }
        return result
    }

    private fun validate(context: BattleDecisionContext, decision: BattleDecision): BattleBrainAttempt =
        when (BattleDecisionValidator.validate(context, decision, nowEpochMillis())) {
            BattleDecisionValidationStatus.VALID -> BattleBrainAttempt.succeeded(decision)
            BattleDecisionValidationStatus.STALE_REQUEST ->
                BattleBrainAttempt.failed(BattleDecisionFailureReason.STALE_REQUEST)
            BattleDecisionValidationStatus.UNKNOWN_ACTION ->
                BattleBrainAttempt.failed(BattleDecisionFailureReason.UNKNOWN_ACTION)
            BattleDecisionValidationStatus.DEADLINE_EXPIRED ->
                BattleBrainAttempt.failed(BattleDecisionFailureReason.DEADLINE_EXPIRED)
        }
}

internal class BattleBrainEndpoint(
    val brain: BattleBrain,
    private val sessionProvider: () -> BattleBrainSession,
) {
    constructor(brain: BattleBrain, session: BattleBrainSession) : this(brain, { session })

    fun session(): BattleBrainSession = sessionProvider()
}

internal class BattleBrainAttempt private constructor(
    val decision: BattleDecision?,
    val failureReason: BattleDecisionFailureReason?,
) {
    init {
        require((decision == null) != (failureReason == null))
    }

    val succeeded: Boolean get() = decision != null

    companion object {
        fun succeeded(decision: BattleDecision) = BattleBrainAttempt(decision, null)
        fun failed(reason: BattleDecisionFailureReason) = BattleBrainAttempt(null, reason)
    }
}

internal enum class BattleDecisionStage { PRIMARY, LOCAL }

internal enum class BattleDecisionFailureReason {
    TIMEOUT,
    BRAIN_FAILURE,
    STALE_REQUEST,
    UNKNOWN_ACTION,
    DEADLINE_EXPIRED,
}

internal data class BattleDecisionFailure(
    val stage: BattleDecisionStage,
    val reason: BattleDecisionFailureReason,
)

internal enum class BattleDecisionSource {
    PRIMARY_BRAIN,
    LOCAL_BRAIN,
    BASELINE_REQUIRED,
    EMERGENCY_ACTION,
}

internal class BattleDecisionResolution private constructor(
    val decision: BattleDecision?,
    val source: BattleDecisionSource,
    failures: List<BattleDecisionFailure>,
) {
    val failures = failures.toList()

    init {
        require((source == BattleDecisionSource.BASELINE_REQUIRED) == (decision == null))
    }

    override fun equals(other: Any?): Boolean = other is BattleDecisionResolution &&
        decision == other.decision && source == other.source && failures == other.failures

    override fun hashCode(): Int = 31 * (31 * (decision?.hashCode() ?: 0) + source.hashCode()) + failures.hashCode()

    companion object {
        fun selected(
            decision: BattleDecision,
            source: BattleDecisionSource,
            failures: List<BattleDecisionFailure> = emptyList(),
        ): BattleDecisionResolution {
            require(source == BattleDecisionSource.PRIMARY_BRAIN || source == BattleDecisionSource.LOCAL_BRAIN)
            return BattleDecisionResolution(decision, source, failures)
        }

        fun baselineRequired(failures: List<BattleDecisionFailure>): BattleDecisionResolution =
            BattleDecisionResolution(null, BattleDecisionSource.BASELINE_REQUIRED, failures)

        fun emergency(
            context: BattleDecisionContext,
            failures: List<BattleDecisionFailure>,
        ): BattleDecisionResolution = BattleDecisionResolution(
            decision = BattleDecision(
                requestId = context.requestId,
                actionId = context.candidates.first().actionId,
                confidence = null,
                tags = setOf("cobblemon_more_battle_content:emergency_fallback"),
            ),
            source = BattleDecisionSource.EMERGENCY_ACTION,
            failures = failures,
        )
    }
}

/** Starts configured Better AI paths together, while preserving primary-before-local selection priority. */
internal class BattleDecisionFallbackChain(
    private val coordinator: BattleBrainDecisionCoordinator,
) {
    fun decide(
        primary: BattleBrainEndpoint?,
        local: BattleBrainEndpoint?,
        context: BattleDecisionContext,
    ): CompletionStage<BattleDecisionResolution> {
        if (primary == null && local == null) {
            return CompletableFuture.completedFuture(BattleDecisionResolution.baselineRequired(emptyList()))
        }
        val primaryAttempt = primary?.let { coordinator.decide(it, context).toCompletableFuture() }
        val localAttempt = local?.let { coordinator.decide(it, context).toCompletableFuture() }

        if (primaryAttempt == null) {
            return localAttempt!!.thenApply { attempt ->
                if (attempt.succeeded) {
                    BattleDecisionResolution.selected(
                        decision = requireNotNull(attempt.decision),
                        source = BattleDecisionSource.LOCAL_BRAIN,
                    )
                } else {
                    BattleDecisionResolution.baselineRequired(
                        listOf(failure(BattleDecisionStage.LOCAL, attempt)),
                    )
                }
            }
        }

        return primaryAttempt.thenCompose { primaryResult ->
            if (primaryResult.succeeded) {
                CompletableFuture.completedFuture(
                    BattleDecisionResolution.selected(
                        decision = requireNotNull(primaryResult.decision),
                        source = BattleDecisionSource.PRIMARY_BRAIN,
                    ),
                )
            } else {
                val primaryFailure = failure(BattleDecisionStage.PRIMARY, primaryResult)
                if (localAttempt == null) {
                    CompletableFuture.completedFuture(
                        BattleDecisionResolution.baselineRequired(listOf(primaryFailure)),
                    )
                } else {
                    localAttempt.thenApply { localResult ->
                        if (localResult.succeeded) {
                            BattleDecisionResolution.selected(
                                decision = requireNotNull(localResult.decision),
                                source = BattleDecisionSource.LOCAL_BRAIN,
                                failures = listOf(primaryFailure),
                            )
                        } else {
                            BattleDecisionResolution.baselineRequired(
                                listOf(primaryFailure, failure(BattleDecisionStage.LOCAL, localResult)),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun failure(stage: BattleDecisionStage, attempt: BattleBrainAttempt) =
        BattleDecisionFailure(stage, requireNotNull(attempt.failureReason))
}
