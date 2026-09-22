package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PvpCompletionRetryQueueTest {
    @Test
    fun `failed settlement waits until its deadline and disappears after a successful retry`() {
        var now = 1_000L
        var attempts = 0
        var available = false
        val pending = PendingPvpCompletion(UUID(0, 1), UUID(0, 2), UUID(0, 3), UUID(0, 4))
        val queue = PvpCompletionRetryQueue(currentTimeMillis = { now }, retryMillis = 5_000L)
        val settle: (PendingPvpCompletion) -> Boolean = {
            attempts++
            available
        }

        assertFalse(queue.submit(pending, settle))
        assertTrue(pending.matchId in queue)
        queue.retryDue(settle = settle)
        assertEquals(1, attempts)

        now = 6_000L
        available = true
        queue.retryDue(settle = settle)

        assertEquals(2, attempts)
        assertFalse(pending.matchId in queue)
        assertEquals(0, queue.size())
    }

    @Test
    fun `linkage failure becomes a retryable settlement failure`() {
        val failure = NoSuchMethodError("record API drift")
        var reported: Throwable? = null

        val settled = attemptPvpCompletionSettlement(
            settle = { throw failure },
            reportFailure = { reported = it },
        )

        assertFalse(settled)
        assertSame(failure, reported)
    }

    @Test
    fun `reporter compatibility failure cannot discard a retryable settlement`() {
        val settled = attemptPvpCompletionSettlement(
            settle = { throw IllegalStateException("record store unavailable") },
            reportFailure = { throw NoSuchMethodError("logger API drift") },
        )

        assertFalse(settled)
    }
}
