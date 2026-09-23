package jbro.cobblemon.morebattlecontent.internal.mixin;

/** Preserves the primary turn failure while exhausting independent recovery steps. */
final class ManagedTurnFailureRecovery {
    private ManagedTurnFailureRecovery() {
    }

    static void recover(
        Throwable primaryFailure,
        boolean canRestoreResponses,
        Runnable rollbackReservation,
        Runnable restoreResponses,
        Runnable abortBattle
    ) {
        attempt(primaryFailure, rollbackReservation);
        if (canRestoreResponses && attempt(primaryFailure, restoreResponses)) {
            return;
        }
        attempt(primaryFailure, abortBattle);
    }

    private static boolean attempt(Throwable primaryFailure, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError cleanupFailure) {
            if (primaryFailure != cleanupFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
            return false;
        }
    }
}
