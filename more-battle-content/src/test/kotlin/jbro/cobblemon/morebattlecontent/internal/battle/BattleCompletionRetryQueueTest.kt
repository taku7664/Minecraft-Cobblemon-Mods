package jbro.cobblemon.morebattlecontent.internal.battle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleCompletionRetryQueueTest {
    @Test
    fun `settled offline completion releases its retained owner`() {
        var cleanups = 0

        val settled = finalizeCompletionOwner(
            settled = true,
            ownerOnline = false,
        ) { cleanups++ }

        assertTrue(settled)
        assertEquals(1, cleanups)
    }

    @Test
    fun `unsettled or online completion retains its owner`() {
        var cleanups = 0

        assertFalse(finalizeCompletionOwner(settled = false, ownerOnline = false) { cleanups++ })
        assertTrue(finalizeCompletionOwner(settled = true, ownerOnline = true) { cleanups++ })

        assertEquals(0, cleanups)
    }

    @Test
    fun `offline cleanup failure keeps completion retryable`() {
        assertThrows(IllegalStateException::class.java) {
            finalizeCompletionOwner(settled = true, ownerOnline = false) {
                error("cleanup failed")
            }
        }
    }

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
    fun `reentrant settlement cannot delete or overwrite a newer completion for the same battle`() {
        val newer = Completion(1, 20)

        listOf(true, false).forEach { outerSucceeded ->
            val queue = BattleCompletionRetryQueue<Int, Completion>(
                keyOf = Completion::battleId,
                currentTimeMillis = { 1_000L },
            )

            assertEquals(
                outerSucceeded,
                queue.submit(Completion(1, 10)) {
                    assertFalse(queue.submit(newer) { false })
                    outerSucceeded
                },
            )

            assertEquals(1, queue.size())
            assertTrue(queue.any { it == newer })
            assertFalse(queue.any { it.playerId == 10 })
        }
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
