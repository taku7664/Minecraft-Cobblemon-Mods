package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded isolation for untrusted Brain calls that may never return a CompletionStage. */
internal object BattleBrainExecutors {
    fun deadlineScheduler(): ScheduledExecutorService = ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "mbc-brain-deadline").apply { isDaemon = true } },
    ).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    fun worker(
        maximumWorkers: Int = defaultWorkerCount(),
        queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    ): ExecutorService {
        require(maximumWorkers > 0)
        require(queueCapacity > 0)
        return ThreadPoolExecutor(
            maximumWorkers,
            maximumWorkers,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(queueCapacity),
            { runnable -> Thread(runnable, "mbc-brain-worker").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private fun defaultWorkerCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(MINIMUM_WORKERS, MAXIMUM_WORKERS)

    private const val MINIMUM_WORKERS = 2
    private const val MAXIMUM_WORKERS = 8
    private const val DEFAULT_QUEUE_CAPACITY = 64
    private const val KEEP_ALIVE_SECONDS = 30L
}
