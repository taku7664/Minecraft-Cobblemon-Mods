package jbro.cobblemon.morebattlecontent.internal.presentation

/** Converts an optional server UI integration failure into a closed result without escaping its event callback. */
internal fun attemptServerUiOperation(
    reportFailure: (Throwable) -> Unit,
    operation: () -> Boolean,
): Boolean = try {
    operation()
} catch (failure: RuntimeException) {
    reportServerUiFailureSafely(failure, reportFailure)
    false
} catch (failure: LinkageError) {
    reportServerUiFailureSafely(failure, reportFailure)
    false
}

private fun reportServerUiFailureSafely(failure: Throwable, reportFailure: (Throwable) -> Unit) {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // Reporting cannot turn a closed UI result back into an event-loop failure.
    } catch (_: LinkageError) {
        // Compatibility reporters are best-effort at optional UI boundaries.
    }
}
