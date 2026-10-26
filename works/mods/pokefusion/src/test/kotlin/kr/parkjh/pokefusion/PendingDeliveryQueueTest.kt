package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PendingDeliveryQueueTest {
    @Test
    fun `an exception after an external grant marks the queue uncertain instead of blindly retrying`() {
        var persisted = listOf("first", "second")
        var blocked = false
        val external = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            PendingDeliveryQueue.deliver(persisted, { persisted = it }, {
                external += it
                error("inventory hook failed after mutation")
            }, { remaining, _ -> persisted = remaining; blocked = true })
        }
        assertEquals(listOf("first"), external)
        assertEquals(listOf("first", "second"), persisted)
        // Production persists this bit in the player attachment; prepare refuses blocked records.
        if (!blocked) PendingDeliveryQueue.deliver(persisted, { persisted = it }, { external += it; null })
        assertEquals(listOf("first"), external)
        assertEquals(true, blocked)
    }

    @Test
    fun `uncertain callback still runs if restoring the queue checkpoint also fails`() {
        var checkpoints = 0
        var held = emptyList<String>()
        assertThrows(IllegalStateException::class.java) {
            PendingDeliveryQueue.deliver(listOf("first"), {
                if (++checkpoints > 1) error("checkpoint failed")
            }, { error("grant threw") }, { remaining, _ -> held = remaining })
        }
        assertEquals(listOf("first"), held)
    }

    @Test
    fun `a fatal stop between checkpoint and grant remains a documented delivery gap`() {
        var persisted = listOf("first")
        assertThrows(AssertionError::class.java) {
            PendingDeliveryQueue.deliver(persisted, { persisted = it }, { throw AssertionError("simulated fatal stop") })
        }
        assertEquals(emptyList<String>(), persisted)
        // This is a limitation, not a claimed successful recovery or an actual server crash test.
    }

    @Test
    fun `first checkpoint failure never invokes grant`() {
        var grants = 0
        assertThrows(IllegalStateException::class.java) {
            PendingDeliveryQueue.deliver(listOf("original"), { error("disk full") }, { grants++; null })
        }
        assertEquals(0, grants)
    }

    @Test
    fun `grant refusal preserves the entry and never visits the tail`() {
        var persisted = listOf("first", "second")
        val visits = mutableListOf<String>()
        assertEquals(0, PendingDeliveryQueue.deliver(persisted, { persisted = it }, { visits += it; it }))
        assertEquals(listOf("first", "second"), persisted)
        assertEquals(listOf("first"), visits)
    }

    @Test
    fun `grant throwing before mutation restores the original queue and propagates`() {
        var persisted = listOf("first", "second")
        assertThrows(IllegalStateException::class.java) {
            PendingDeliveryQueue.deliver(persisted, { persisted = it }, { error("inventory rejected before mutation") })
        }
        assertEquals(listOf("first", "second"), persisted)
    }

    @Test
    fun `reloading the latest checkpoint does not redeliver completed entries`() {
        var persisted = listOf("first", "second")
        val granted = mutableListOf<String>()
        assertEquals(1, PendingDeliveryQueue.deliver(persisted, { persisted = it }, { if (it == "first") { granted += it; null } else it }))
        assertEquals(listOf("second"), persisted)
        assertEquals(1, PendingDeliveryQueue.deliver(persisted.toList(), { persisted = it }, { granted += it; null }))
        assertEquals(listOf("first", "second"), granted)
        assertEquals(emptyList<String>(), persisted)
    }

    @Test
    fun `completed item is not restored when next checkpoint fails`() {
        var persisted = listOf("first", "second")
        val granted = mutableListOf<String>()
        var checkpointCount = 0

        assertThrows(IllegalStateException::class.java) {
            PendingDeliveryQueue.deliver(
                pending = persisted,
                checkpoint = { remaining ->
                    checkpointCount++
                    if (checkpointCount == 2) error("disk full")
                    persisted = remaining
                },
                grant = { item -> granted += item; null }
            )
        }

        assertEquals(listOf("first"), granted)
        assertEquals(listOf("second"), persisted)
    }

    @Test
    fun `failed grant checkpoints its actual remainder and untouched tail`() {
        var persisted = listOf(5, 7)

        val delivered = PendingDeliveryQueue.deliver(
            pending = persisted,
            checkpoint = { persisted = it },
            grant = { item -> if (item == 5) 2 else null }
        )

        assertEquals(0, delivered)
        assertEquals(listOf(2, 7), persisted)
    }

    @Test
    fun `successful delivery leaves an empty durable queue`() {
        var persisted = listOf("first", "second")

        val delivered = PendingDeliveryQueue.deliver(
            pending = persisted,
            checkpoint = { persisted = it },
            grant = { null }
        )

        assertEquals(2, delivered)
        assertEquals(emptyList<String>(), persisted)
    }
}
