package jbro.cobblemon.mcc.betterai.brain

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.outcome.PublicMechanicalActionGate
import jbro.cobblemon.mcc.betterai.policy.forPlanOwner
import jbro.cobblemon.mcc.betterai.router.BetterAiConfig
import jbro.cobblemon.mcc.betterai.router.HumanlikePromptCodec
import jbro.cobblemon.mcc.betterai.router.LocalBrainSignals
import jbro.cobblemon.mcc.betterai.router.OpenRouterDecisionSummaryDiagnostics
import jbro.cobblemon.mcc.betterai.router.OpenRouterDecisionSummaryRecord
import jbro.cobblemon.mcc.betterai.router.OpenRouterDecisionSummarySink
import jbro.cobblemon.mcc.betterai.router.OpenRouterModelCapabilities
import jbro.cobblemon.mcc.betterai.router.OpenRouterRequestDiagnostics
import org.slf4j.LoggerFactory

internal fun interface OpenRouterTransport {
    fun complete(requestJson: String, timeoutMillis: Long): CompletionStage<String>
}

internal class OpenRouterHttpTransport(
    private val config: BetterAiConfig,
    executor: Executor,
) : OpenRouterTransport {
    private val client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5)).build()

    override fun complete(requestJson: String, timeoutMillis: Long): CompletionStage<String> {
        val startedAtNanos = System.nanoTime()
        val builder = HttpRequest.newBuilder(URI.create(config.endpoint))
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
        if (config.appUrl.isNotBlank()) builder.header("HTTP-Referer", config.appUrl)
        if (config.appName.isNotBlank()) builder.header("X-OpenRouter-Title", config.appName)
        val httpFuture = client.sendAsync(
            builder.POST(HttpRequest.BodyPublishers.ofString(requestJson)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val contentFuture = CompletableFuture<String>()
        httpFuture.whenComplete { response, throwable ->
            val elapsedMillis = elapsedMillis(startedAtNanos)
            if (throwable != null) {
                logger.warn(
                    "OpenRouter decision request failed: {}",
                    OpenRouterRequestDiagnostics.transportFailure(throwable, elapsedMillis),
                )
                contentFuture.completeExceptionally(throwable)
            } else {
                val statusCode = response.statusCode()
                if (statusCode !in 200..299) {
                    logger.warn(
                        "OpenRouter decision request failed: {}",
                        OpenRouterRequestDiagnostics.httpFailure(statusCode, elapsedMillis),
                    )
                    contentFuture.completeExceptionally(
                        IllegalStateException("OpenRouter returned HTTP $statusCode"),
                    )
                    return@whenComplete
                }
                logger.info(
                    "OpenRouter decision request completed: {}",
                    OpenRouterRequestDiagnostics.httpSuccess(statusCode, elapsedMillis),
                )
                runCatching {
                    JsonParser.parseString(response.body()).asJsonObject
                        .getAsJsonArray("choices")[0].asJsonObject
                        .getAsJsonObject("message").get("content").asString
                }.fold(contentFuture::complete, contentFuture::completeExceptionally)
            }
        }
        contentFuture.whenComplete { _, _ ->
            if (contentFuture.isCancelled) httpFuture.cancel(true)
        }
        return contentFuture
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))

    private companion object {
        val logger = LoggerFactory.getLogger("more_cobblemon_contents")
    }
}

internal class OpenRouterTacticalBrain(
    private val config: BetterAiConfig,
    private val transport: OpenRouterTransport,
    private val modelCapabilities: () -> OpenRouterModelCapabilities? = { null },
    private val decisionSummarySink: OpenRouterDecisionSummarySink = OpenRouterDecisionSummarySink.NONE,
    private val decisionSummaryExecutor: Executor = ForkJoinPool.commonPool(),
) : BattleBrain {
    /** The stages of the local decision running on this thread, caught by the local brain's stage trace. */
    private val localStages = ThreadLocal<LocalDecisionStages?>()

    /**
     * The local brain, played first on every decision so the model sees its evaluation ([LocalBrainSignals]); null when
     * both weights are 0. It keeps its own session, as it would deciding alone, and records its decisions the same way.
     */
    private val local: LocalTacticalBrain? = if (config.localBrainWeight > 0.0 || config.opponentTendencyWeight > 0.0) {
        LocalTacticalBrain(searchless = true, stageTrace = { localStages.set(it) })
    } else null

    override fun openSession(context: BattleBrainOpenContext): BattleBrainSession = Session(
        sessionId = UUID.randomUUID(),
        openContext = context,
        localSession = local?.let { brain ->
            runCatching { brain.openSession(context) }
                .onFailure { logger.warn("[MCC Better AI] Local brain session for the router could not open: {}", it.javaClass.name) }
                .getOrNull()
        },
    )

    /** The local brain's evaluation of [context]; null when it is off or fails, and the model then decides without it. */
    private fun localSignals(session: Session, context: BattleDecisionContext): LocalBrainSignals? {
        val brain = local ?: return null
        val localSession = session.localSession ?: return null
        localStages.remove()
        return try {
            brain.decide(localSession, context).toCompletableFuture().getNow(null)
            localStages.get()?.let { LocalBrainSignals(it, config.localBrainWeight, config.opponentTendencyWeight) }
        } catch (exception: Exception) {
            logger.warn("[MCC Better AI] Local brain evaluation for the router failed: {}", exception.javaClass.name)
            null
        } finally {
            localStages.remove()
        }
    }

    override fun decide(session: BattleBrainSession, context: BattleDecisionContext): CompletionStage<BattleDecision> {
        val active = session as? Session ?: return CompletableFuture.failedFuture(IllegalArgumentException("Unknown session"))
        val calculatedContext = PublicBattleTacticalCalculator.calculate(context)
            .forPlanOwner(jbro.cobblemon.mcc.internal.ai.BattlePlanOwner.PRIMARY_BRAIN)
            .let(PublicMechanicalActionGate::removeNullifiedWhenPossible)
        if (calculatedContext.candidates.size == 1) {
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = calculatedContext.requestId,
                    actionId = calculatedContext.candidates.single().actionId,
                    confidence = 1.0,
                    tags = setOf("single_legal_action"),
                ),
            )
        }
        val signals = localSignals(active, context)
        val remaining = (calculatedContext.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L)
        val configured = if (config.softTimeoutMillis > 0) config.softTimeoutMillis else config.timeoutMillis
        val timeout = minOf(remaining, configured, config.timeoutMillis)
        return try {
            val transportFuture = transport.complete(
                HumanlikePromptCodec.requestJson(
                    config,
                    active.openContext,
                    calculatedContext,
                    modelCapabilities(),
                    signals,
                ),
                timeout,
            ).toCompletableFuture()
            val decisionFuture = CompletableFuture<BattleDecision>()
            transportFuture.whenComplete { content, throwable ->
                if (throwable != null) {
                    decisionFuture.completeExceptionally(throwable)
                } else {
                    runCatching {
                        HumanlikePromptCodec.parseDecision(
                            content,
                            calculatedContext,
                            config.mindGamesEnabled,
                            active.openContext.trainerProfile.difficulty.tier,
                        )
                    }.fold(
                        onSuccess = { decision ->
                            // Whether or not its stages reached the prompt, the local brain read its own pick as played.
                            active.localSession?.let { localSession ->
                                calculatedContext.candidates.firstOrNull { it.actionId == decision.actionId }?.let { played ->
                                    runCatching { local?.recordPlayed(localSession, calculatedContext.state.turn, played) }
                                }
                            }
                            decisionFuture.complete(decision)
                            if (config.logDecisionSummary) {
                                publishDecisionSummary(active, calculatedContext, decision, content)
                            }
                        },
                        onFailure = decisionFuture::completeExceptionally,
                    )
                }
            }
            decisionFuture.whenComplete { _, _ ->
                if (decisionFuture.isCancelled) transportFuture.cancel(true)
            }
            decisionFuture
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) {
        val localSession = (session as? Session)?.localSession ?: return
        runCatching { local?.closeSession(localSession, result) }
    }

    private fun publishDecisionSummary(
        session: Session,
        context: BattleDecisionContext,
        decision: BattleDecision,
        content: String,
    ) {
        val record = runCatching {
            OpenRouterDecisionSummaryRecord.create(
                battleId = session.openContext.battleId.toString(),
                turn = context.state.turn,
                actionId = decision.actionId,
                difficulty = session.openContext.trainerProfile.difficulty.tier.name,
                rawSummary = HumanlikePromptCodec.parseDecisionSummary(content),
            )
        }.getOrElse { exception ->
            logger.warn(
                "[MCC Better AI] Router decision summary could not be parsed: failure_type={}",
                exception.javaClass.name,
            )
            return
        }
        logger.info(
            "[MCC Better AI] Router decision summary: {}",
            OpenRouterDecisionSummaryDiagnostics.format(
                battleId = record.battleId,
                turn = record.turn,
                actionId = record.actionId,
                difficulty = record.difficulty,
                rawSummary = record.summary,
            ),
        )
        try {
            decisionSummaryExecutor.execute {
                try {
                    decisionSummarySink.append(record)
                } catch (exception: Exception) {
                    logger.warn(
                        "[MCC Better AI] Router decision summary journal append failed: failure_type={}",
                        exception.javaClass.name,
                    )
                }
            }
        } catch (exception: Exception) {
            logger.warn(
                "[MCC Better AI] Router decision summary journal dispatch failed: failure_type={}",
                exception.javaClass.name,
            )
        }
    }

    private data class Session(
        override val sessionId: UUID,
        val openContext: BattleBrainOpenContext,
        val localSession: BattleBrainSession? = null,
    ) : BattleBrainSession

    private companion object {
        val logger = LoggerFactory.getLogger("more_cobblemon_contents")
    }
}
