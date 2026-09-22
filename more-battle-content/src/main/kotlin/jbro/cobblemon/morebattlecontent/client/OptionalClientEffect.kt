package jbro.cobblemon.morebattlecontent.client

internal inline fun runOptionalClientEffect(
    action: () -> Unit,
    recover: (Throwable) -> Unit = {},
    reportFailure: (Throwable) -> Unit,
) {
    try {
        action()
    } catch (failure: RuntimeException) {
        handleOptionalClientEffectFailure(failure, recover, reportFailure)
    } catch (failure: LinkageError) {
        handleOptionalClientEffectFailure(failure, recover, reportFailure)
    }
}

private inline fun handleOptionalClientEffectFailure(
    failure: Throwable,
    recover: (Throwable) -> Unit,
    reportFailure: (Throwable) -> Unit,
) {
    runOptionalClientEffectCallback { recover(failure) }
    runOptionalClientEffectCallback { reportFailure(failure) }
}

private inline fun runOptionalClientEffectCallback(action: () -> Unit) {
    try {
        action()
    } catch (_: RuntimeException) {
        // Optional effect recovery and reporting cannot break the render loop.
    } catch (_: LinkageError) {
        // Client integration callbacks are best-effort across supported dependency versions.
    }
}

internal inline fun <T> releaseOptionalClientResourcesSafely(
    resources: Iterable<T>,
    release: (T) -> Unit,
    reportFailure: (Throwable) -> Unit,
) {
    resources.forEach { resource ->
        runOptionalClientEffect(
            action = { release(resource) },
            reportFailure = reportFailure,
        )
    }
}
