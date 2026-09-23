package jbro.cobblemon.morebattlecontent.internal.pvp.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorRecoveryTest {
    @Test
    fun `failed pending return is reported as a failed spectator recovery`() {
        var rescued = false
        var notified = false

        val recovered = attemptUntrackedPvpSpectatorRecovery(
            hasPendingReturn = true,
            inLoungeDimension = true,
            restorePending = { false },
            rescueStranded = { rescued = true; true },
            notifyInactive = { notified = true },
        )

        assertFalse(recovered)
        assertFalse(rescued)
        assertFalse(notified)
    }

    @Test
    fun `failed fallback rescue is not acknowledged as a completed exit`() {
        val recovered = attemptUntrackedPvpSpectatorRecovery(
            hasPendingReturn = false,
            inLoungeDimension = true,
            restorePending = { true },
            rescueStranded = { false },
            notifyInactive = {},
        )

        assertFalse(recovered)
    }

    @Test
    fun `an already safe player only needs the inactive client notification`() {
        var notified = false

        val recovered = attemptUntrackedPvpSpectatorRecovery(
            hasPendingReturn = false,
            inLoungeDimension = false,
            restorePending = { false },
            rescueStranded = { false },
            notifyInactive = { notified = true },
        )

        assertTrue(recovered)
        assertTrue(notified)
    }
}
