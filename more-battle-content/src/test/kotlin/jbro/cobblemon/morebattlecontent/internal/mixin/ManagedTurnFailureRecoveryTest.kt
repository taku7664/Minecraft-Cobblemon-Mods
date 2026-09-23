package jbro.cobblemon.morebattlecontent.internal.mixin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedTurnFailureRecoveryTest {
    @Test
    fun `reservation rollback failure does not skip response restoration`() {
        val primary = IllegalStateException("submission failed")
        val rollbackFailure = IllegalArgumentException("rollback failed")
        val calls = mutableListOf<String>()

        ManagedTurnFailureRecovery.recover(
            primary,
            true,
            Runnable {
                calls += "rollback"
                throw rollbackFailure
            },
            Runnable { calls += "restore" },
            Runnable { calls += "abort" },
        )

        assertEquals(listOf("rollback", "restore"), calls)
        assertEquals(listOf(rollbackFailure), primary.suppressed.toList())
    }

    @Test
    fun `failed response restoration falls back to aborting the battle`() {
        val primary = IllegalStateException("submission failed")
        val restoreFailure = IllegalArgumentException("restore failed")
        val abortFailure = NoSuchMethodError("abort failed")
        val calls = mutableListOf<String>()

        ManagedTurnFailureRecovery.recover(
            primary,
            true,
            Runnable { calls += "rollback" },
            Runnable {
                calls += "restore"
                throw restoreFailure
            },
            Runnable {
                calls += "abort"
                throw abortFailure
            },
        )

        assertEquals(listOf("rollback", "restore", "abort"), calls)
        assertEquals(listOf(restoreFailure, abortFailure), primary.suppressed.toList())
    }

    @Test
    fun `fatal cleanup errors still propagate`() {
        val fatal = object : Error("fatal") {}

        assertThrows(fatal.javaClass) {
            ManagedTurnFailureRecovery.recover(
                IllegalStateException("submission failed"),
                false,
                Runnable { throw fatal },
                Runnable {},
                Runnable {},
            )
        }
    }
}
