package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

internal fun <T> protectManagedBattleStartup(
    releasePendingRegistration: () -> Unit,
    terminateBattle: () -> Unit,
    setup: () -> T,
): T = try {
    setup()
} catch (failure: RuntimeException) {
    abortFailedManagedBattleStartup(failure, releasePendingRegistration, terminateBattle)
} catch (failure: LinkageError) {
    abortFailedManagedBattleStartup(failure, releasePendingRegistration, terminateBattle)
}

internal fun runManagedCleanupActions(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (cleanupFailure: RuntimeException) {
            val original = failure
            if (original == null) failure = cleanupFailure else original.suppressDistinct(cleanupFailure)
        } catch (cleanupFailure: LinkageError) {
            val original = failure
            if (original == null) failure = cleanupFailure else original.suppressDistinct(cleanupFailure)
        }
    }
    failure?.let { throw it }
}

internal fun runManagedCleanupActionsSafely(
    reportFailure: (Throwable) -> Unit,
    vararg actions: () -> Unit,
) {
    try {
        runManagedCleanupActions(*actions)
    } catch (failure: RuntimeException) {
        reportManagedCleanupFailureSafely(failure, reportFailure)
    } catch (failure: LinkageError) {
        reportManagedCleanupFailureSafely(failure, reportFailure)
    }
}

internal fun <T> runManagedCleanupForEachSafely(
    items: Iterable<T>,
    reportFailure: (T, Throwable) -> Unit,
    action: (T) -> Unit,
) {
    items.forEach { item ->
        runManagedCleanupActionsSafely(
            reportFailure = { failure -> reportFailure(item, failure) },
            { action(item) },
        )
    }
}

internal fun reportManagedCleanupFailureSafely(failure: Throwable, reportFailure: (Throwable) -> Unit) {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // Failure reporting cannot interrupt the surrounding lifecycle boundary.
    } catch (_: LinkageError) {
        // Compatibility reporters are best-effort during cleanup.
    }
}

private fun abortFailedManagedBattleStartup(
    failure: Throwable,
    releasePendingRegistration: () -> Unit,
    terminateBattle: () -> Unit,
): Nothing {
    try {
        terminateBattle()
    } catch (cleanupFailure: RuntimeException) {
        failure.suppressDistinct(cleanupFailure)
    } catch (cleanupFailure: LinkageError) {
        failure.suppressDistinct(cleanupFailure)
    }
    try {
        releasePendingRegistration()
    } catch (cleanupFailure: RuntimeException) {
        failure.suppressDistinct(cleanupFailure)
    } catch (cleanupFailure: LinkageError) {
        failure.suppressDistinct(cleanupFailure)
    }
    throw failure
}

internal fun Throwable.suppressDistinct(other: Throwable) {
    if (this !== other) addSuppressed(other)
}
