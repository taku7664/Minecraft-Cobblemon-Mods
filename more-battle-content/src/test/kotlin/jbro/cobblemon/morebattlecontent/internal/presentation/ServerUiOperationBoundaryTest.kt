package jbro.cobblemon.morebattlecontent.internal.presentation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerUiOperationBoundaryTest {
    @Test
    fun `successful operation keeps its result`() {
        assertTrue(attemptServerUiOperation(reportFailure = {}) { true })
        assertFalse(attemptServerUiOperation(reportFailure = {}) { false })
    }

    @Test
    fun `runtime failure is reported and closed`() {
        val failure = IllegalStateException("packet send failed")
        var reported: Throwable? = null

        assertFalse(attemptServerUiOperation(reportFailure = { reported = it }) { throw failure })
        assertSame(failure, reported)
    }

    @Test
    fun `linkage failure and broken reporter cannot escape`() {
        assertFalse(
            attemptServerUiOperation(
                reportFailure = { throw IllegalStateException("logger unavailable") },
                operation = { throw NoSuchMethodError("network API drift") },
            ),
        )
    }
}
