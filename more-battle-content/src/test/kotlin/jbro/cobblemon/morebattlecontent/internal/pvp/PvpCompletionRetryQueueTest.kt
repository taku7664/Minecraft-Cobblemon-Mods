package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PvpCompletionRetryQueueTest {
    @Test
    fun `stale battle transition cannot clean up a newer room generation`() {
        var cleanupCalls = 0

        assertFalse(
            transitionPvpBattleLifecycle(
                transition = { false },
                cleanup = { cleanupCalls++ },
            ),
        )
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `accepted battle transition cleans up its room generation once`() {
        var cleanupCalls = 0

        assertTrue(
            transitionPvpBattleLifecycle(
                transition = { true },
                cleanup = { cleanupCalls++ },
            ),
        )
        assertEquals(1, cleanupCalls)
    }

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
    fun `completion retries from different battles in one rematch room do not replace each other`() {
        val matchId = UUID(0, 10)
        val first = PendingPvpCompletion(matchId, UUID(0, 11), UUID(0, 12), UUID(0, 13))
        val second = PendingPvpCompletion(matchId, UUID(0, 21), UUID(0, 22), UUID(0, 23))
        val queue = PvpCompletionRetryQueue(currentTimeMillis = { 1_000L }, retryMillis = 5_000L)

        assertFalse(queue.submit(first) { false })
        assertFalse(queue.submit(second) { false })

        assertEquals(2, queue.size())
        assertTrue(matchId in queue)

        queue.retryDue(force = true) { pending -> pending.battleId == first.battleId }

        assertEquals(1, queue.size())
        assertTrue(matchId in queue)
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
