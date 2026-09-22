package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PvpLoungeExitRequestStateTest {
    @Test
    fun `exit screen closes only after an accepted server response`() {
        val state = PvpLoungeExitRequestState()

        assertTrue(state.begin())
        assertFalse(state.begin(), "a pending request must not be sent twice")
        assertFalse(state.complete(accepted = false))
        assertTrue(state.begin(), "a rejected request must leave retry available")
        assertTrue(state.complete(accepted = true))
    }

    @Test
    fun `failed exit send releases the request for retry`() {
        val state = PvpLoungeExitRequestState()
        val failure = AssertionError("network send failed")

        assertEquals(failure, assertThrows<AssertionError> { state.send { throw failure } })
        assertTrue(state.send {})
    }
}
