package jbro.cobblemon.morebattlecontent.internal.battle

import java.util.UUID

/**
 * Commits the authoritative result before invoking an external termination callback.
 *
 * Battle termination may synchronously re-enter the owning session through an end handler.
 * Settling first makes that callback observe an already-finished battle. Termination is still
 * attempted when settlement fails so a persistence failure cannot leave a live orphan battle.
 */
internal inline fun settleBeforeTerminatingBattle(
    battleId: UUID,
    settle: (UUID) -> Unit,
    terminate: (UUID) -> Unit,
) {
    try {
        settle(battleId)
    } catch (settlementFailure: Throwable) {
        try {
            terminate(battleId)
        } catch (terminationFailure: Throwable) {
            if (terminationFailure !== settlementFailure) {
                settlementFailure.addSuppressed(terminationFailure)
            }
        }
        throw settlementFailure
    }
    terminate(battleId)
}
