package jbro.cobblemon.morebattlecontent.internal.presentation

internal inline fun runOptionalProjectionSend(
    action: () -> Unit,
    reportFailure: (Throwable) -> Unit,
) {
    try {
        action()
    } catch (failure: RuntimeException) {
        reportProjectionFailureSafely(failure, reportFailure)
    } catch (failure: LinkageError) {
        reportProjectionFailureSafely(failure, reportFailure)
    }
}

private inline fun reportProjectionFailureSafely(
    failure: Throwable,
    reportFailure: (Throwable) -> Unit,
) {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // A broken reporter cannot turn an optional projection failure into a battle lifecycle failure.
    } catch (_: LinkageError) {
        // Optional compatibility logging is best-effort at this boundary.
    }
}
