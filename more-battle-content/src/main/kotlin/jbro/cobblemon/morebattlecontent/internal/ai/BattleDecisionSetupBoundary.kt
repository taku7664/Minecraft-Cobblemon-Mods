package jbro.cobblemon.morebattlecontent.internal.ai

/** Contains compatibility-sensitive turn preparation and hands failures to the required fallback path. */
internal inline fun <T> attemptBattleDecisionSetup(
    setup: () -> T,
    recover: (Throwable) -> Unit,
): T? = try {
    setup()
} catch (failure: Exception) {
    recover(failure)
    null
} catch (failure: LinkageError) {
    recover(failure)
    null
}

/** Contains compatibility-sensitive turn completion without letting one failed step strand the request. */
internal inline fun attemptBattleDecisionCompletion(
    complete: () -> Unit,
    recover: (Throwable) -> Unit,
): Boolean = try {
    complete()
    true
} catch (failure: Exception) {
    recover(failure)
    false
} catch (failure: LinkageError) {
    recover(failure)
    false
}

/** Builds a fallback response through one preferred path and one compatibility-safe last resort. */
internal fun <T> prepareBattleDecisionFallback(
    preferred: () -> T?,
    lastResort: () -> T,
    report: (Throwable) -> Unit,
): T? {
    var preferredFailure: Throwable? = null
    val preferredValue = try {
        preferred()
    } catch (failure: Exception) {
        preferredFailure = failure
        null
    } catch (failure: LinkageError) {
        preferredFailure = failure
        null
    }
    if (preferredValue != null) return preferredValue

    val lastResortValue = try {
        lastResort()
    } catch (lastResortFailure: Exception) {
        reportBattleDecisionFallbackFailure(preferredFailure, lastResortFailure, report)
        return null
    } catch (lastResortFailure: LinkageError) {
        reportBattleDecisionFallbackFailure(preferredFailure, lastResortFailure, report)
        return null
    }
    preferredFailure?.let { reportBattleDecisionFallbackSafely(it, report) }
    return lastResortValue
}

private fun reportBattleDecisionFallbackFailure(
    preferredFailure: Throwable?,
    lastResortFailure: Throwable,
    report: (Throwable) -> Unit,
) {
    val primary = preferredFailure ?: lastResortFailure
    if (preferredFailure != null && preferredFailure !== lastResortFailure) {
        preferredFailure.addSuppressed(lastResortFailure)
    }
    reportBattleDecisionFallbackSafely(primary, report)
}

private fun reportBattleDecisionFallbackSafely(primary: Throwable, report: (Throwable) -> Unit) {
    try {
        report(primary)
    } catch (reportFailure: Exception) {
        if (primary !== reportFailure) primary.addSuppressed(reportFailure)
    } catch (reportFailure: LinkageError) {
        if (primary !== reportFailure) primary.addSuppressed(reportFailure)
    }
}
