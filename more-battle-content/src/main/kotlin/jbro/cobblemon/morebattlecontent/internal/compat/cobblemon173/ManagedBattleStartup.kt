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

private fun Throwable.suppressDistinct(other: Throwable) {
    if (this !== other) addSuppressed(other)
}
