package jbro.cobblemon.morebattlecontent.betterai

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
import jbro.cobblemon.morebattlecontent.api.ai.*
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
        val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai")
    }
}

internal class OpenRouterTacticalBrain(
    private val config: BetterAiConfig,
    private val transport: OpenRouterTransport,
    private val modelCapabilities: () -> OpenRouterModelCapabilities? = { null },
    private val decisionSummarySink: OpenRouterDecisionSummarySink = OpenRouterDecisionSummarySink.NONE,
    private val decisionSummaryExecutor: Executor = ForkJoinPool.commonPool(),
) : BattleBrain {
    override fun openSession(context: BattleBrainOpenContext): BattleBrainSession = Session(
        sessionId = UUID.randomUUID(),
        openContext = context,
    )

    override fun decide(session: BattleBrainSession, context: BattleDecisionContext): CompletionStage<BattleDecision> {
        val active = session as? Session ?: return CompletableFuture.failedFuture(IllegalArgumentException("Unknown session"))
        val calculatedContext = PublicBattleTacticalCalculator.calculate(context)
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

    override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) = Unit

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
                "[MBC Better AI] Router decision summary could not be parsed: failure_type={}",
                exception.javaClass.name,
            )
            return
        }
        logger.info(
            "[MBC Better AI] Router decision summary: {}",
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
                        "[MBC Better AI] Router decision summary journal append failed: failure_type={}",
                        exception.javaClass.name,
                    )
                }
            }
        } catch (exception: Exception) {
            logger.warn(
                "[MBC Better AI] Router decision summary journal dispatch failed: failure_type={}",
                exception.javaClass.name,
            )
        }
    }

    private data class Session(
        override val sessionId: UUID,
        val openContext: BattleBrainOpenContext,
    ) : BattleBrainSession

    private companion object {
        val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai")
    }
}
