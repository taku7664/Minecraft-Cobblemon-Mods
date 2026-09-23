package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpLoungeExitResultTest {
    @Test
    fun `accepted exit deactivates controls even without the separate spectator state packet`() {
        val request = PendingClientRequest().also { it.begin() }
        var deactivated = false

        val closeScreen = applyPvpLoungeExitResult(
            request = request,
            accepted = true,
            deactivateControls = { deactivated = true },
            clearExitPending = {},
        )

        assertTrue(closeScreen)
        assertTrue(deactivated)
    }

    @Test
    fun `rejected exit remains active and only clears the pending button state`() {
        val request = PendingClientRequest().also { it.begin() }
        var deactivated = false
        var pendingCleared = false

        val closeScreen = applyPvpLoungeExitResult(
            request = request,
            accepted = false,
            deactivateControls = { deactivated = true },
            clearExitPending = { pendingCleared = true },
        )

        assertFalse(closeScreen)
        assertFalse(deactivated)
        assertTrue(pendingCleared)
    }
}
