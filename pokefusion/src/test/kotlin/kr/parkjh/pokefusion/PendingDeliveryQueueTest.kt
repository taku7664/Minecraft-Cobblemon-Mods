package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PendingDeliveryQueueTest {
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
