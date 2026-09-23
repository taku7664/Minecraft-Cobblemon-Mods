package jbro.cobblemon.morebattlecontent.internal.pvp

/**
 * Keeps a prepared match provisional until both players can be moved into its selection UI.
 * A failed notification must not leave snapshots, timers, or room phase changes behind.
 */
internal inline fun protectPreparedPvpMatchNotification(
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
