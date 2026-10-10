package jbro.cobblemon.policy.admin

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConsoleCommandQueueTest {
    private val player = UUID.randomUUID()

    @Test fun `only permission four admits console commands`() {
        val queue = ConsoleCommandQueue()
        for (level in 0..3) assertEquals(ConsoleCommandQueue.Rejection.PERMISSION, queue.submit(player, level, "list"))
        assertNull(queue.submit(player, 4, "list"))
        val commands = mutableListOf<String>()
        queue.drain({ 4 }, { commands += it.command }, { fail("Unexpected denial") })
        assertEquals(listOf("list"), commands)
    }

    @Test fun `one leading slash is optional and quoted arguments are retained`() {
        val queue = ConsoleCommandQueue()
        assertNull(queue.submit(player, 4, "  /say \"two words\"  "))
        queue.drain({ 4 }, { assertEquals("say \"two words\"", it.command) }, { fail("Unexpected denial") })
    }

    @Test fun `empty controls and oversized input never reach execution`() {
        val queue = ConsoleCommandQueue()
        for (input in listOf("", "  ", "/")) assertEquals(ConsoleCommandQueue.Rejection.EMPTY, queue.submit(player, 4, input))
        for (input in listOf("list\nstop", "list\r", "list\u0000", "list\tstop")) {
            assertEquals(ConsoleCommandQueue.Rejection.INVALID, queue.submit(player, 4, input))
        }
        assertEquals(ConsoleCommandQueue.Rejection.TOO_LONG, queue.submit(player, 4, "a".repeat(2049)))
        queue.drain({ 4 }, { fail("Invalid input executed") }, { fail("Unexpected denial") })
    }

    @Test fun `permission is rechecked after admission and offline players are rejected`() {
        val queue = ConsoleCommandQueue()
        val offline = UUID.randomUUID()
        queue.submit(player, 4, "list")
        queue.submit(offline, 4, "list")
        val denied = mutableListOf<UUID>()
        queue.drain({ if (it == offline) null else 3 }, { fail("Permission revoked but command executed") }, { denied += it.playerId })
        assertEquals(listOf(player, offline), denied)
    }

    @Test fun `indirect recursive admission is blocked while execution is active`() {
        val queue = ConsoleCommandQueue()
        queue.submit(player, 4, "execute as admin run servercmd list")
        var executions = 0
        queue.drain({ 4 }, {
            executions++
            assertTrue(queue.isExecuting)
            assertEquals(ConsoleCommandQueue.Rejection.RECURSION, queue.submit(player, 4, "list"))
        }, { fail("Unexpected denial") })
        queue.drain({ 4 }, { executions++ }, { fail("Unexpected denial") })
        assertEquals(1, executions)
        assertFalse(queue.isExecuting)
    }

    @Test fun `execution failure releases the recursion guard`() {
        val queue = ConsoleCommandQueue()
        queue.submit(player, 4, "list")
        assertThrows(IllegalStateException::class.java) {
            queue.drain({ 4 }, { throw IllegalStateException("Injected failure") }, {})
        }
        assertFalse(queue.isExecuting)
        assertNull(queue.submit(player, 4, "list"))
        var executions = 0
        queue.drain({ 4 }, { executions++ }, {})
        assertEquals(1, executions)
    }

    @Test fun `queue bounds pending requests and limits work per tick`() {
        val queue = ConsoleCommandQueue()
        repeat(32) { assertNull(queue.submit(player, 4, "say $it")) }
        assertEquals(ConsoleCommandQueue.Rejection.BUSY, queue.submit(player, 4, "list"))
        val commands = mutableListOf<String>()
        queue.drain({ 4 }, { commands += it.command }, {})
        assertEquals(listOf("say 0", "say 1", "say 2", "say 3"), commands)
        assertNull(queue.submit(player, 4, "list"))
    }

    @Test fun `stopped server discards pending requests`() {
        val queue = ConsoleCommandQueue()
        queue.submit(player, 4, "stop")
        queue.clear()
        queue.drain({ 4 }, { fail("Stale request executed") }, {})
        assertFalse(queue.isExecuting)
    }
}
