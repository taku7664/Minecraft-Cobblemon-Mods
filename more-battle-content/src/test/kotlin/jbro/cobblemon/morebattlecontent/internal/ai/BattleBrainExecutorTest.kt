package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BattleBrainExecutorTest {
    @Test
    fun `blocked Brain calls cannot create unbounded workers or queue entries`() {
        val release = CountDownLatch(1)
        val executor = BattleBrainExecutors.worker(maximumWorkers = 1, queueCapacity = 1) as ThreadPoolExecutor
        val blockingCall = Runnable {
            try {
                release.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            executor.execute(blockingCall)
            executor.execute(blockingCall)

            assertThrows(RejectedExecutionException::class.java) {
                executor.execute(blockingCall)
            }
            assertEquals(1, executor.maximumPoolSize)
            assertEquals(1, executor.queue.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
