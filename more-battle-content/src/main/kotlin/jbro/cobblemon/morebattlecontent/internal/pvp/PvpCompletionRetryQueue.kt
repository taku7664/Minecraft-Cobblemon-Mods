package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.battle.BattleCompletionRetryQueue
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely

internal const val PVP_COMPLETION_RETRY_MILLIS = 5_000L

internal data class PendingPvpCompletion(
    val matchId: UUID,
    val battleId: UUID,
    val winnerId: UUID,
    val loserId: UUID,
)

internal fun attemptPvpCompletionSettlement(
    settle: () -> Boolean,
    reportFailure: (Throwable) -> Unit,
): Boolean = try {
    settle()
} catch (failure: RuntimeException) {
    reportManagedCleanupFailureSafely(failure, reportFailure)
    false
} catch (failure: LinkageError) {
    reportManagedCleanupFailureSafely(failure, reportFailure)
    false
}

internal fun transitionPvpBattleLifecycle(
    transition: () -> Boolean,
    isStillPending: () -> Boolean = { true },
    cleanup: () -> Unit,
): Boolean {
    try {
        if (!transition()) return false
    } catch (failure: RuntimeException) {
        cleanupCommittedPvpTransition(failure, isStillPending, cleanup)
        throw failure
    } catch (failure: LinkageError) {
        cleanupCommittedPvpTransition(failure, isStillPending, cleanup)
        throw failure
    }
    cleanup()
    return true
}

private fun cleanupCommittedPvpTransition(
    failure: Throwable,
    isStillPending: () -> Boolean,
    cleanup: () -> Unit,
) {
    val pending = try {
        isStillPending()
    } catch (inspectionFailure: Throwable) {
        if (failure !== inspectionFailure) failure.addSuppressed(inspectionFailure)
        return
    }
    if (pending) return
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    }
}

/** Keeps a finished PvP result retryable while its paired persistent record is unavailable. */
internal class PvpCompletionRetryQueue(
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    retryMillis: Long = PVP_COMPLETION_RETRY_MILLIS,
) {
    private val entries = BattleCompletionRetryQueue<UUID, PendingPvpCompletion>(
        keyOf = PendingPvpCompletion::battleId,
        currentTimeMillis = currentTimeMillis,
        retryMillis = retryMillis,
    )

    @Synchronized
    fun submit(completion: PendingPvpCompletion, settle: (PendingPvpCompletion) -> Boolean): Boolean =
        entries.submit(completion, settle)

    @Synchronized
    fun retryDue(force: Boolean = false, settle: (PendingPvpCompletion) -> Boolean) =
        entries.retryDue(force, settle)

    @Synchronized
    operator fun contains(matchId: UUID): Boolean = entries.any { it.matchId == matchId }

    @Synchronized
    fun size(): Int = entries.size()

    @Synchronized
    fun clear() = entries.clear()
}
