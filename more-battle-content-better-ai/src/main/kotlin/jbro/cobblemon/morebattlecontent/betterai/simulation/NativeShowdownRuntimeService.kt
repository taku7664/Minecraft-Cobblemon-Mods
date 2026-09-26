package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/** Server lifecycle boundary for the isolated native Showdown generation. */
internal object NativeShowdownRuntimeService {
    private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai/native_showdown")
    private val lifecycle = AtomicReference<NativeShowdownServerLifecycle?>()
    private val pendingGeneration = AtomicReference<CompletableFuture<Boolean>?>()

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
                    val startedAt = System.nanoTime()
                    val generation = if (megaShowdownLoaded) {
                        MegaShowdownNativeRulesProvider.capture(engineRoot)
                    } else {
                        NativeRulesGeneration.capture(engineRoot)
                    }
                    val capturedAt = System.nanoTime()
                    val pool = NativeShowdownWorkerPool.open(generation, DEFAULT_WORKER_COUNT)
                    val readyAt = System.nanoTime()
                    logger.info(
                        "Native Showdown prepared: rules_ms={} worker_ms={} total_ms={} fingerprint={}",
                        (capturedAt - startedAt) / 1_000_000,
                        (readyAt - capturedAt) / 1_000_000,
                        (readyAt - startedAt) / 1_000_000,
                        generation.fingerprint.take(12),
                    )
                    pool
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
            installed.start().also { pendingGeneration.set(it); observe(it, "server start") }
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, successful ->
            if (successful) {
                installed.reload()?.let { pendingGeneration.set(it); observe(it, "data pack reload") }
            } else {
                installed.invalidate()
                pendingGeneration.set(null)
                logger.warn("Data pack reload failed; native Showdown projection was disabled until a successful reload")
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            pendingGeneration.set(null)
            installed.stop()
        }
    }

    fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? {
        val pending = pendingGeneration.get()
        if (pending != null && !pending.isDone) {
            val remaining = deadlineNanos - System.nanoTime()
            val waitNanos = minOf(remaining, MAX_INITIAL_READY_WAIT_NANOS)
            if (waitNanos > 0) {
                try {
                    pending.get(waitNanos, TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    logger.warn("Native Showdown is still preparing after {} ms; this decision will use local lookahead",
                        TimeUnit.NANOSECONDS.toMillis(waitNanos))
                } catch (_: java.util.concurrent.ExecutionException) {
                    // The generation failure handler logged the cause; the caller uses local lookahead.
                } catch (_: CancellationException) {
                    // Stopping or replacing a generation must still allow the caller's fallback.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return lifecycle.get()?.withWorker(deadlineNanos, action)
    }

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
    private val MAX_INITIAL_READY_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10)
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
