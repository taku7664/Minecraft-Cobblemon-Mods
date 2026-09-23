package jbro.cobblemon.morebattlecontent.internal.battle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleCompletionRetryQueueTest {
    private data class Completion(val battleId: Int, val playerId: Int)

    @Test
    fun `failed settlement waits until due and successful retry removes it`() {
        var now = 1_000L
        var available = false
        var attempts = 0
        val queue = BattleCompletionRetryQueue<Int, Completion>(
            keyOf = Completion::battleId,
            currentTimeMillis = { now },
            retryMillis = 5_000L,
        )
        val completion = Completion(1, 10)

        assertFalse(queue.submit(completion) { attempts++; available })
        queue.retryDue { attempts++; available }
        assertEquals(1, attempts)

        now = 6_000L
        available = true
        queue.retryDue { attempts++; available }

        assertEquals(2, attempts)
        assertEquals(0, queue.size())
    }

    @Test
    fun `battle generations remain independent and can be queried by owner`() {
        val queue = BattleCompletionRetryQueue<Int, Completion>(
            keyOf = Completion::battleId,
            currentTimeMillis = { 1_000L },
        )
        queue.submit(Completion(1, 10)) { false }
        queue.submit(Completion(2, 10)) { false }
        queue.submit(Completion(3, 20)) { false }

        assertTrue(queue.any { it.playerId == 10 })
        queue.retryDue(force = true) { it.battleId == 1 }

        assertEquals(2, queue.size())
        assertTrue(queue.any { it.playerId == 10 })
        assertTrue(queue.any { it.playerId == 20 })
        assertFalse(queue.any { it.playerId == 30 })
    }

    @Test
    fun `clock rollback does not postpone a completion until the old wall time returns`() {
        var now = 100_000L
        var available = false
        var attempts = 0
        val queue = BattleCompletionRetryQueue<Int, Completion>(
            keyOf = Completion::battleId,
            currentTimeMillis = { now },
            retryMillis = 5_000L,
        )

        assertFalse(queue.submit(Completion(1, 10)) { attempts++; available })

        now = 10_000L
        queue.retryDue { attempts++; available }
        assertEquals(1, attempts)

        now = 15_000L
        available = true
        queue.retryDue { attempts++; available }

        assertEquals(2, attempts)
        assertEquals(0, queue.size())
    }
}
