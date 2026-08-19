package jbro.cobblemon.morebattlecontent.betterai

import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenRouterRequestDiagnosticsTest {
    @Test
    fun `http diagnostics contain status and latency without response content`() {
        assertEquals(
            "outcome=HTTP_FAILURE http_status=401 elapsed_ms=77",
            OpenRouterRequestDiagnostics.httpFailure(statusCode = 401, elapsedMillis = 77L),
        )
    }

    @Test
    fun `transport diagnostics unwrap only the failure type and redact its message`() {
        val secret = "must-not-appear-in-server-log"
        val diagnostics = OpenRouterRequestDiagnostics.transportFailure(
            throwable = CompletionException(IllegalStateException(secret)),
            elapsedMillis = 88L,
        )

        assertEquals("outcome=TRANSPORT_FAILURE failure_type=java.lang.IllegalStateException elapsed_ms=88", diagnostics)
        assertTrue(diagnostics.contains("java.lang.IllegalStateException"))
        assertFalse(diagnostics.contains(secret))
    }

    @Test
    fun `decision summary diagnostics remove log injection and bound model text`() {
        val summary = "  공개 상성 우위\r\n[ERROR] 공격 후보를 선택했습니다.\u0000  " + "가".repeat(300)

        val diagnostics = OpenRouterDecisionSummaryDiagnostics.format(
            battleId = "battle\r\nforged",
            turn = 7,
            actionId = "move\nforged",
            difficulty = "BOSS",
            rawSummary = summary,
        )

        assertFalse(diagnostics.contains('\n'))
        assertFalse(diagnostics.contains('\r'))
        assertFalse(diagnostics.contains('\u0000'))
        assertTrue(diagnostics.startsWith("battle=battle_forged turn=7 action_id=move_forged difficulty=BOSS summary="))
        assertTrue(diagnostics.substringAfter(" summary=").length <= 240)
    }
}
