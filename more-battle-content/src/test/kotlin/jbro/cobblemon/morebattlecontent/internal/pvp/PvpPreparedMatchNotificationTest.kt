package jbro.cobblemon.morebattlecontent.internal.pvp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PvpPreparedMatchNotificationTest {
    @Test
    fun `notification failure rolls prepared match back and preserves the original failure`() {
        val events = ArrayList<String>()
        val notificationFailure = IllegalStateException("send failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            protectProvisionalPvpStateNotification(
                rollback = { events += "rollback" },
            ) {
                events += "notify"
                throw notificationFailure
            }
        }

        assertSame(notificationFailure, thrown)
        assertEquals(listOf("notify", "rollback"), events)
    }

    @Test
    fun `rollback failure is suppressed onto notification failure`() {
        val notificationFailure = NoSuchMethodError("network API drift")
        val rollbackFailure = IllegalStateException("rollback failed")

        val thrown = assertThrows(NoSuchMethodError::class.java) {
            protectProvisionalPvpStateNotification(
                rollback = { throw rollbackFailure },
            ) {
                throw notificationFailure
            }
        }

        assertSame(notificationFailure, thrown)
        assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
    }

    @Test
    fun `successful notification keeps prepared match intact`() {
        var rolledBack = false

        protectProvisionalPvpStateNotification(
            rollback = { rolledBack = true },
        ) {}

        assertEquals(false, rolledBack)
    }
}
