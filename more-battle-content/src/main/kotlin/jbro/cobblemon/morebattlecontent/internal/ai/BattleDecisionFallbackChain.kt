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
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import java.util.concurrent.ConcurrentHashMap

/** Runs an untrusted Brain call off-thread and resolves it exactly once before the shared deadline. */
internal class BattleBrainDecisionCoordinator(
    private val scheduler: ScheduledExecutorService,
    private val brainExecutor: Executor = ForkJoinPool.commonPool(),
    private val maximumDecisionMillis: Long = BattleBrainDefaults.DECISION_TIMEOUT_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val brainFailureCounts = ConcurrentHashMap<String, Int>()

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
                } catch (exception: Exception) {
                    if (result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE))) {
                        timeout.cancel(false)
                    }
                    reportBrainFailure(context, exception)
                    return@execute
                } catch (error: LinkageError) {
                    if (result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE))) {
                        timeout.cancel(false)
                    }
                    reportBrainFailure(context, error)
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
                    val failed = throwable != null || decision == null
                    val attempt = if (failed) {
                        BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE)
                    } else {
                        validate(context, decision)
                    }
                    if (result.complete(attempt)) timeout.cancel(false)
                    if (failed) throwable?.let { reportBrainFailure(context, it) }
                }
            }
        } catch (exception: Exception) {
            timeout.cancel(false)
            result.complete(BattleBrainAttempt.failed(BattleDecisionFailureReason.BRAIN_FAILURE))
            reportBrainFailure(context, exception)
        }
        return result
    }

    /**
     * Says why a Brain failed, once per distinct fault.
     *
     * A Brain is untrusted, so the coordinator has always been right to catch everything and fall
     * back. What it also did was discard the reason, and a Brain that throws on every turn then looks
     * exactly like one that is merely unlucky: the battle log reported `BRAIN_FAILURE` on 50 of 54
     * decisions in one session with no way to learn what threw. Falling back is the correct behaviour;
     * doing it silently is not.
     *
     * The first sighting of a fault carries its stack trace, later ones only a count, so a fault that
     * repeats every turn stays one readable entry rather than flooding the log.
     *
     * Always called after the attempt has been completed, never before. Initialising the logger costs
     * a few hundred milliseconds the first time, and a decision has a deadline: reporting the failure
     * must not be what makes the fallback miss it. `BattleDecisionFallbackChainTest` holds this.
     */
    private fun reportBrainFailure(context: BattleDecisionContext, throwable: Throwable) {
        val cause = generateSequence(throwable) { it.cause }.last()
        val signature = "${cause.javaClass.name}:${cause.message.orEmpty()}"
        if (!brainFailureCounts.containsKey(signature) && brainFailureCounts.size >= MAX_DISTINCT_FAILURE_SIGNATURES) return
        val seen = brainFailureCounts.merge(signature, 1, Int::plus) ?: 1
        if (seen == 1) {
            MoreBattleContent.LOGGER.error(
                "Battle {} turn {} Brain threw with {} candidates; falling back",
                runCatching { context.state.battleId }.getOrNull(),
                runCatching { context.state.turn }.getOrNull(),
                context.candidates.size,
                throwable,
            )
        } else if (seen % REPEATED_FAILURE_LOG_INTERVAL == 0) {
            MoreBattleContent.LOGGER.error(
                "Battle {} Brain has now thrown {} times with {}",
                runCatching { context.state.battleId }.getOrNull(),
                seen,
                signature,
            )
        }
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

private const val REPEATED_FAILURE_LOG_INTERVAL = 25
private const val MAX_DISTINCT_FAILURE_SIGNATURES = 256

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
