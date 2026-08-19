package jbro.cobblemon.morebattlecontent.betterai

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Restricts Router request logs to bounded operational metadata. */
internal object OpenRouterRequestDiagnostics {
    fun httpSuccess(statusCode: Int, elapsedMillis: Long): String =
        "outcome=HTTP_SUCCESS http_status=$statusCode elapsed_ms=${elapsedMillis.coerceAtLeast(0L)}"

    fun httpFailure(statusCode: Int, elapsedMillis: Long): String =
        "outcome=HTTP_FAILURE http_status=$statusCode elapsed_ms=${elapsedMillis.coerceAtLeast(0L)}"

    fun transportFailure(throwable: Throwable, elapsedMillis: Long): String =
        "outcome=TRANSPORT_FAILURE failure_type=${rootCauseType(throwable)} " +
            "elapsed_ms=${elapsedMillis.coerceAtLeast(0L)}"

    private fun rootCauseType(throwable: Throwable): String {
        var current = throwable
        while (current is CompletionException || current is ExecutionException) {
            current = current.cause ?: break
        }
        return current.javaClass.name
    }
}
