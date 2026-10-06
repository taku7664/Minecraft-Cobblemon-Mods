package jbro.cobblemon.mcc.internal.battle;

/** Preserves the primary turn failure while exhausting independent recovery steps. Outside the mixin package, which mixins may not load classes from. */
public final class ManagedTurnFailureRecovery {
    private ManagedTurnFailureRecovery() {
    }

    public static void recover(
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

    public static void releaseReservation(Throwable primaryFailure, Runnable rollbackReservation) {
        attempt(primaryFailure, rollbackReservation);
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
