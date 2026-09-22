package jbro.cobblemon.morebattlecontent.internal.persistence

internal inline fun <T> loadSavedDataSafely(
    load: () -> T,
    reportFailure: (Throwable) -> Unit,
    unavailable: () -> T,
): T = try {
    load()
} catch (failure: RuntimeException) {
    recoverUnavailableSavedData(failure, reportFailure, unavailable)
} catch (failure: LinkageError) {
    recoverUnavailableSavedData(failure, reportFailure, unavailable)
}

private inline fun <T> recoverUnavailableSavedData(
    failure: Throwable,
    reportFailure: (Throwable) -> Unit,
    unavailable: () -> T,
): T {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // Reporting cannot prevent the original serialized data from being preserved.
    } catch (_: LinkageError) {
        // Compatibility reporters are best-effort while loading persisted data.
    }
    return unavailable()
}
