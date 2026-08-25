package jbro.cobblemon.morebattlecontent.betterai

import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainFactory
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProvider
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistrationStatus
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionPolicy
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.ai.BrainId
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.brain.OpenRouterHttpTransport
import jbro.cobblemon.morebattlecontent.betterai.brain.OpenRouterTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfig
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfigStore
import jbro.cobblemon.morebattlecontent.betterai.router.JsonlOpenRouterDecisionSummarySink
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterDecisionSummarySink
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelCapabilityCache
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelMetadata
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelMetadataHttpTransport
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelMetadataTransport
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

object MoreBattleContentBetterAi : ModInitializer {
    const val MOD_ID: String = "cobblemon_more_battle_content_better_ai"

    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        val loader = FabricLoader.getInstance()
        val config = try {
            BetterAiConfigStore.loadOrCreate(
                loader.configDir
                    .resolve("cobblemon-more-battle-content-better-ai")
                    .resolve("openrouter.json"),
            )
        } catch (exception: Exception) {
            logger.error("Better AI config could not be loaded; external decisions are disabled: {}", exception.javaClass.name)
            BetterAiConfig()
        }
        val summaryPath = loader.gameDir.resolve("logs").resolve(DECISION_SUMMARY_FILE_NAME)
        val summaryExecutor = if (config.logDecisionSummary) createDecisionSummaryExecutor() else null
        val summarySink = if (config.logDecisionSummary) {
            logger.info("Router decision summaries will be appended to {}", summaryPath)
            JsonlOpenRouterDecisionSummarySink(summaryPath)
        } else {
            OpenRouterDecisionSummarySink.NONE
        }
        register(
            BattleBrainRegistry.global(),
            config,
            modelMetadataTransportFactory = { uri, timeout, executor ->
                OpenRouterModelMetadataHttpTransport(uri, timeout, executor)
            },
            decisionSummarySink = summarySink,
            decisionSummaryExecutor = summaryExecutor ?: ForkJoinPool.commonPool(),
        )
        summaryExecutor?.let(::registerDecisionSummaryShutdown)
    }

    internal fun register(
        registry: BattleBrainRegistry,
        config: BetterAiConfig,
        modelMetadataTransportFactory: ((URI, Long, java.util.concurrent.Executor) -> OpenRouterModelMetadataTransport)? = null,
        decisionSummarySink: OpenRouterDecisionSummarySink = OpenRouterDecisionSummarySink.NONE,
        decisionSummaryExecutor: Executor = ForkJoinPool.commonPool(),
    ) {
        val localStatus = registry.register(
            BattleBrainProvider(
                id = BrainId("$MOD_ID:local_tactical"),
                capabilities = setOf(BrainCapability.SINGLE, BrainCapability.DOUBLE),
                factory = BattleBrainFactory(::LocalTacticalBrain),
                role = BattleBrainProviderRole.LOCAL,
                selectionPolicy = BattleBrainSelectionPolicy.ALWAYS,
            ),
        )
        if (localStatus != BattleBrainRegistrationStatus.REGISTERED) {
            logger.error("Better AI local tactical provider registration failed: {}", localStatus)
        }
        if (!config.externallyUsable) return

        val queue: BlockingQueue<Runnable> = if (config.maximumQueuedRequests == 0) {
            SynchronousQueue()
        } else {
            ArrayBlockingQueue(config.maximumQueuedRequests)
        }
        val executor = ThreadPoolExecutor(
            config.maximumConcurrentRequests,
            config.maximumConcurrentRequests,
            30,
            TimeUnit.SECONDS,
            queue,
            { runnable -> Thread(runnable, "mbc-openrouter").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val modelCapabilityCache = modelMetadataTransportFactory
            ?.let { factory -> OpenRouterModelMetadata.metadataUri(URI.create(config.endpoint), config.model)?.let { it to factory } }
            ?.let { (metadataUri, factory) ->
                OpenRouterModelCapabilityCache(
                    factory(metadataUri, config.timeoutMillis, executor),
                ).also { it.refresh() }
            }
        val primaryStatus = registry.register(
            BattleBrainProvider(
                id = BrainId("$MOD_ID:openrouter_humanlike"),
                capabilities = setOf(BrainCapability.SINGLE, BrainCapability.DOUBLE),
                factory = BattleBrainFactory {
                    OpenRouterTacticalBrain(
                        config,
                        OpenRouterHttpTransport(config, executor),
                        modelCapabilities = { modelCapabilityCache?.current() },
                        decisionSummarySink = decisionSummarySink,
                        decisionSummaryExecutor = decisionSummaryExecutor,
                    )
                },
                role = BattleBrainProviderRole.PRIMARY,
                selectionPolicy = BattleBrainSelectionPolicy(config.routerPolicy::allows),
            ),
        )
        if (primaryStatus != BattleBrainRegistrationStatus.REGISTERED) {
            executor.shutdownNow()
            logger.error("Better AI OpenRouter provider registration failed: {}", primaryStatus)
        }
    }

    private fun createDecisionSummaryExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mbc-router-summary-journal").apply { isDaemon = true }
    }

    private fun registerDecisionSummaryShutdown(executor: ExecutorService) {
        ServerLifecycleEvents.SERVER_STOPPING.register {
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Router decision summary journal did not flush before server shutdown")
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                executor.shutdownNow()
            }
        }
    }

    private const val DECISION_SUMMARY_FILE_NAME = "mbc-better-ai-router-decisions.jsonl"
}
