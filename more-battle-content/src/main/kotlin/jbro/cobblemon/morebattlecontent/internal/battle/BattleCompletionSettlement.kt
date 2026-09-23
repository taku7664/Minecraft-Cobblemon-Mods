package jbro.cobblemon.morebattlecontent.internal.battle

import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely

/**
 * Separates persistent settlement from best-effort post-settlement notifications. Once [settle]
 * returns, its result is committed and must not be put back into a persistence retry queue merely
 * because a client update or diagnostic failed.
 */
internal fun <T> attemptBattleCompletionSettlement(
    settle: () -> T,
    afterSettlement: (T) -> Unit,
    reportSettlementFailure: (Throwable) -> Unit,
    reportNotificationFailure: (Throwable) -> Unit,
): Boolean {
    val settled = try {
        settle()
    } catch (failure: RuntimeException) {
        reportManagedCleanupFailureSafely(failure, reportSettlementFailure)
        return false
    } catch (failure: LinkageError) {
        reportManagedCleanupFailureSafely(failure, reportSettlementFailure)
        return false
    }
    try {
        afterSettlement(settled)
    } catch (failure: RuntimeException) {
        reportManagedCleanupFailureSafely(failure, reportNotificationFailure)
    } catch (failure: LinkageError) {
        reportManagedCleanupFailureSafely(failure, reportNotificationFailure)
    }
    return true
}
