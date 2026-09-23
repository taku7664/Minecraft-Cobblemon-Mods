package jbro.cobblemon.morebattlecontent.internal.catalog

/** Prevents one optional catalog from aborting the enclosing server-data reload. */
internal fun runCatalogReloadSafely(
    reload: () -> Unit,
    reportFailure: (Throwable) -> Unit,
) {
    try {
        reload()
    } catch (failure: RuntimeException) {
        reportCatalogReloadFailureSafely(failure, reportFailure)
    } catch (failure: LinkageError) {
        reportCatalogReloadFailureSafely(failure, reportFailure)
    }
}

private fun reportCatalogReloadFailureSafely(failure: Throwable, reportFailure: (Throwable) -> Unit) {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // Logging cannot replace the previous valid catalog or abort the enclosing resource reload.
    } catch (_: LinkageError) {
        // Compatibility logging is best-effort during resource reload.
    }
}
