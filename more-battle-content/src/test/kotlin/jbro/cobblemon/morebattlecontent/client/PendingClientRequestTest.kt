package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PendingClientRequestTest {
    @Test
    fun `successful send stays pending until the response completes it`() {
        val request = PendingClientRequest()
        var sends = 0

        assertTrue(request.send { sends += 1 })
        assertTrue(request.isPending)
        assertFalse(request.send { sends += 1 })
        assertEquals(1, sends)
        assertFalse(request.complete(accepted = false))
        assertFalse(request.isPending)
        assertTrue(request.send { sends += 1 })
        assertTrue(request.complete(accepted = true))
    }

    @Test
    fun `failed send releases the request and preserves the original failure`() {
        val request = PendingClientRequest()
        val failure = AssertionError("network send failed")

        assertEquals(failure, assertThrows<AssertionError> { request.send { throw failure } })
        assertFalse(request.isPending)
        assertTrue(request.send {})
    }
}
