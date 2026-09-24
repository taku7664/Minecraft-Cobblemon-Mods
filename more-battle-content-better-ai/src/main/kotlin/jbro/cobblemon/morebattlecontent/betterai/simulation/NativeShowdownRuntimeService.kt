package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/** Server lifecycle boundary for the isolated native Showdown generation. */
internal object NativeShowdownRuntimeService {
    private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai/native_showdown")
    private val lifecycle = AtomicReference<NativeShowdownServerLifecycle?>()

    fun install(loader: FabricLoader) {
        val engineRoot = loader.gameDir.resolve("showdown")
        val megaShowdownLoaded = loader.isModLoaded(MEGA_SHOWDOWN_MOD_ID)
        val installed = NativeShowdownServerLifecycle {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mbc-native-showdown-generation").apply { isDaemon = true }
            }
            NativeShowdownRuntime(
                executor = executor,
                poolFactory = {
                    val generation = if (megaShowdownLoaded) {
                        MegaShowdownNativeRulesProvider.capture(engineRoot)
                    } else {
                        NativeRulesGeneration.capture(engineRoot)
                    }
                    NativeShowdownWorkerPool.open(generation, DEFAULT_WORKER_COUNT)
                },
                failureHandler = { failure ->
                    logger.error(
                        "Native Showdown generation is unavailable; native projection remains disabled: {}",
                        failure.javaClass.name,
                        failure,
                    )
                },
            )
        }
        check(lifecycle.compareAndSet(null, installed)) { "Native Showdown lifecycle was installed twice" }

        ServerLifecycleEvents.SERVER_STARTED.register {
            observe(installed.start(), "server start")
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, successful ->
            if (successful) {
                installed.reload()?.let { observe(it, "data pack reload") }
            } else {
                installed.invalidate()
                logger.warn("Data pack reload failed; native Showdown projection was disabled until a successful reload")
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            installed.stop()
        }
    }

    fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? =
        lifecycle.get()?.withWorker(deadlineNanos, action)

    private fun observe(refresh: CompletableFuture<Boolean>, reason: String) {
        refresh.whenComplete { activated, failure ->
            when {
                failure != null -> Unit // The runtime's failure handler already emitted the root cause.
                activated -> logger.info("Native Showdown generation activated after {}", reason)
            }
        }
    }

    private const val MEGA_SHOWDOWN_MOD_ID = "mega_showdown"
    private const val DEFAULT_WORKER_COUNT = 1
}

/** Creates and disposes one asynchronous native runtime for each Minecraft server lifetime. */
internal class NativeShowdownServerLifecycle(
    private val runtimeFactory: () -> NativeShowdownRuntime,
) {
    private val runtime = AtomicReference<NativeShowdownRuntime?>()

    fun start(): CompletableFuture<Boolean> {
        val created = runtimeFactory()
        runtime.getAndSet(created)?.close()
        return created.refresh()
    }

    fun reload(): CompletableFuture<Boolean>? = runtime.get()?.refresh()

    fun invalidate() {
        runtime.get()?.invalidate()
    }

    fun stop() {
        runtime.getAndSet(null)?.close()
    }

    fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? =
        runtime.get()?.withWorker(deadlineNanos, action)
}
