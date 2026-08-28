package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
}
