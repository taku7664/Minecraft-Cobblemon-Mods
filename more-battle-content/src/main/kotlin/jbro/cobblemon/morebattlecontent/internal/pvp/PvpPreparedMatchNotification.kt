package jbro.cobblemon.morebattlecontent.internal.pvp

/**
 * Keeps newly committed PvP state provisional until its required notification succeeds. A failed
 * notification must not leave invitations, snapshots, timers, or room phase changes behind.
 */
internal inline fun protectProvisionalPvpStateNotification(
    rollback: () -> Unit,
    notify: () -> Unit,
) {
    try {
        notify()
    } catch (failure: RuntimeException) {
        rollbackPreparedPvpMatch(failure, rollback)
    } catch (failure: LinkageError) {
        rollbackPreparedPvpMatch(failure, rollback)
    }
}

private inline fun rollbackPreparedPvpMatch(failure: Throwable, rollback: () -> Unit): Nothing {
    try {
        rollback()
    } catch (cleanupFailure: RuntimeException) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    } catch (cleanupFailure: LinkageError) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
}
