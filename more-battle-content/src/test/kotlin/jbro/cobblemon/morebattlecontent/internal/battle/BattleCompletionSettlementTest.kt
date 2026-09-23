package jbro.cobblemon.morebattlecontent.internal.battle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleCompletionSettlementTest {
    @Test
    fun `settlement failure stays retryable and skips notification`() {
        val failure = IllegalStateException("record store unavailable")
        var notified = false
        var reported: Throwable? = null

        val settled = attemptBattleCompletionSettlement(
            settle = { throw failure },
            afterSettlement = { _: String -> notified = true },
            reportSettlementFailure = { reported = it },
            reportNotificationFailure = {},
        )

        assertFalse(settled)
        assertFalse(notified)
        assertSame(failure, reported)
    }

    @Test
    fun `notification failure cannot turn committed settlement into a retry`() {
        val failure = NoSuchMethodError("packet API drift")
        var reported: Throwable? = null

        val settled = attemptBattleCompletionSettlement(
            settle = { "committed" },
            afterSettlement = { throw failure },
            reportSettlementFailure = {},
            reportNotificationFailure = { reported = it },
        )

        assertTrue(settled)
        assertSame(failure, reported)
    }

    @Test
    fun `notification reporter failure cannot resurrect a committed result`() {
        val settled = attemptBattleCompletionSettlement(
            settle = { "committed" },
            afterSettlement = { throw IllegalStateException("send failed") },
            reportSettlementFailure = {},
            reportNotificationFailure = { throw NoSuchMethodError("logger API drift") },
        )

        assertTrue(settled)
    }
}
