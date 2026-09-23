package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PvpLoungeRestoreNotificationTest {
    @Test
    fun `client notification failure cannot turn a completed lounge restore into a retry`() {
        val failure = IllegalStateException("connection closed during restore notification")
        var reported: Throwable? = null

        assertDoesNotThrow {
            notifyCompletedPvpLoungeRestore(
                notifyClient = { throw failure },
                reportFailure = { reported = it },
            )
        }

        assertSame(failure, reported)
    }

    @Test
    fun `notification reporter failure cannot escape a completed lounge restore`() {
        assertDoesNotThrow {
            notifyCompletedPvpLoungeRestore(
                notifyClient = { throw NoSuchMethodError("packet API drift") },
                reportFailure = { throw IllegalStateException("logger unavailable") },
            )
        }
    }
}
