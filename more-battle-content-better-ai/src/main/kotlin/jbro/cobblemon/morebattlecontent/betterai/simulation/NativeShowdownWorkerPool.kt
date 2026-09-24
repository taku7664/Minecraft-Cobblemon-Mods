package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import org.slf4j.LoggerFactory

/** A fixed, bounded set of isolated Graal workers that all execute one immutable rules generation. */
internal class NativeShowdownWorkerPool private constructor(
    private val generation: NativeRulesGeneration,
    private val workers: List<NativeBranchWorker>,
) {
    private val available = ArrayBlockingQueue<NativeBranchWorker>(workers.size).apply { addAll(workers) }
    private val lifecycleLock = Any()
    private var leases = 0
    private var retired = false
    private var resourcesClosed = false

    fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? {
        synchronized(lifecycleLock) {
            if (retired) return null
            leases++
        }
        var worker: NativeBranchWorker? = null
        try {
            while (worker == null) {
                synchronized(lifecycleLock) {
                    if (retired) return null
                }
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) return null
                worker = available.poll(min(remaining, RETIRE_POLL_NANOS), TimeUnit.NANOSECONDS)
            }
            synchronized(lifecycleLock) {
                if (retired) return null
            }
            return action(worker)
        } finally {
            worker?.let { check(available.offer(it)) { "Native worker queue overflowed" } }
            releaseLease()
        }
    }

    fun retire() {
        val closeNow = synchronized(lifecycleLock) {
            retired = true
            markClosedIfIdle()
        }
        if (closeNow) closeResources()
    }

    private fun releaseLease() {
        val closeNow = synchronized(lifecycleLock) {
            check(leases > 0) { "Native worker lease underflow" }
            leases--
            markClosedIfIdle()
        }
        if (closeNow) closeResources()
    }

    private fun markClosedIfIdle(): Boolean {
        if (!retired || leases != 0 || resourcesClosed) return false
        resourcesClosed = true
        return true
    }

    private fun closeResources() {
        var failure: Throwable? = null
        workers.forEach { worker ->
            try {
                worker.close()
            } catch (caught: Throwable) {
                failure?.addSuppressed(caught) ?: run { failure = caught }
            }
        }
        try {
            generation.close()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        failure?.let { logger.error("Native Showdown generation did not close cleanly", it) }
    }

    companion object {
        fun open(generation: NativeRulesGeneration, workerCount: Int): NativeShowdownWorkerPool {
            require(workerCount in 1..MAX_WORKERS) { "Native Showdown worker count must be between 1 and $MAX_WORKERS" }
            val workers = mutableListOf<NativeBranchWorker>()
            try {
                repeat(workerCount) {
                    workers += NativeShowdownBranchEngine.open(generation.sourceEngineRoot, generation)
                }
                return create(generation, workers)
            } catch (failure: Throwable) {
                workers.forEach { runCatching { it.close() } }
                generation.close()
                throw failure
            }
        }

        internal fun create(
            generation: NativeRulesGeneration,
            workers: List<NativeBranchWorker>,
        ): NativeShowdownWorkerPool {
            require(workers.isNotEmpty()) { "A native Showdown generation needs at least one worker" }
            require(workers.all { it.rulesFingerprint == generation.fingerprint }) {
                "Every native worker must execute the pool's rules generation"
            }
            return NativeShowdownWorkerPool(generation, workers.toList())
        }

        private const val MAX_WORKERS = 4
        private val RETIRE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10)
        private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai/native_showdown")
    }
}

/**
 * Owns asynchronous rules-generation refreshes. Refresh immediately removes stale workers from
 * selection; only a completely built newest generation becomes visible.
 */
internal class NativeShowdownRuntime(
    private val executor: ExecutorService,
    private val poolFactory: () -> NativeShowdownWorkerPool,
    private val failureHandler: (Throwable) -> Unit = {},
    private val beforeActivation: () -> Unit = {},
) : AutoCloseable {
    private val stateLock = Any()
    private var revision = 0L
    private var active: NativeShowdownWorkerPool? = null
    private var closed = false

    fun refresh(): CompletableFuture<Boolean> {
        val request = synchronized(stateLock) {
            if (closed) {
                null
            } else {
                revision++
                val stale = active
                active = null
                revision to stale
            }
        } ?: return CompletableFuture.completedFuture(false)
        val (requestedRevision, stale) = request
        val result = CompletableFuture<Boolean>()
        try {
            executor.execute {
                retire(stale)
                val stillCurrent = synchronized(stateLock) { !closed && revision == requestedRevision }
                if (!stillCurrent) {
                    result.complete(false)
                    return@execute
                }
                var replacement: NativeShowdownWorkerPool? = null
                try {
                    replacement = poolFactory()
                    beforeActivation()
                    var displaced: NativeShowdownWorkerPool? = null
                    val activated = synchronized(stateLock) {
                        if (closed || revision != requestedRevision) {
                            false
                        } else {
                            displaced = active
                            active = replacement
                            true
                        }
                    }
                    if (!activated) {
                        retire(replacement)
                        result.complete(false)
                    } else {
                        retire(displaced)
                        result.complete(true)
                    }
                } catch (failure: Throwable) {
                    retire(replacement)
                    reportFailure(failure)
                    result.completeExceptionally(failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            retireOnDaemon(stale)
            result.complete(false)
        }
        return result
    }

    fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? {
        val selected = synchronized(stateLock) { if (closed) null else active }
        return selected?.withWorker(deadlineNanos, action)
    }

    fun invalidate() {
        val stale = synchronized(stateLock) {
            if (closed) return
            revision++
            val selected = active
            active = null
            selected
        }
        scheduleRetirement(stale)
    }

    override fun close() {
        val stale = synchronized(stateLock) {
            if (closed) return
            closed = true
            revision++
            val selected = active
            active = null
            selected
        }
        scheduleRetirement(stale)
        executor.shutdown()
    }

    private fun scheduleRetirement(stale: NativeShowdownWorkerPool?) {
        try {
            executor.execute { retire(stale) }
        } catch (_: RejectedExecutionException) {
            retireOnDaemon(stale)
        }
    }

    private fun retireOnDaemon(pool: NativeShowdownWorkerPool?) {
        if (pool == null) return
        Thread({ retire(pool) }, "mbc-native-showdown-retire").apply { isDaemon = true }.start()
    }

    private fun retire(pool: NativeShowdownWorkerPool?) {
        if (pool == null) return
        try {
            pool.retire()
        } catch (failure: Throwable) {
            reportFailure(failure)
        }
    }

    private fun reportFailure(failure: Throwable) {
        runCatching { failureHandler(failure) }
    }
}
