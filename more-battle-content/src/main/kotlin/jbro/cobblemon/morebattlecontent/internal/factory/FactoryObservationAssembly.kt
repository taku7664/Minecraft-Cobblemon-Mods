package jbro.cobblemon.morebattlecontent.internal.factory

internal inline fun <K, V> assembleFactoryObservationsOrEmpty(
    assemble: () -> Map<K, V>,
    reportFailure: (Throwable) -> Unit,
): Map<K, V> = try {
    assemble()
} catch (failure: RuntimeException) {
    reportFactoryObservationFailureSafely(failure, reportFailure)
    emptyMap()
} catch (failure: LinkageError) {
    reportFactoryObservationFailureSafely(failure, reportFailure)
    emptyMap()
}

private inline fun reportFactoryObservationFailureSafely(
    failure: Throwable,
    reportFailure: (Throwable) -> Unit,
) {
    try {
        reportFailure(failure)
    } catch (_: RuntimeException) {
        // Diagnostics cannot replace the safe empty observation fallback.
    } catch (_: LinkageError) {
        // Compatibility reporting is best-effort while a battle is settling.
    }
}
