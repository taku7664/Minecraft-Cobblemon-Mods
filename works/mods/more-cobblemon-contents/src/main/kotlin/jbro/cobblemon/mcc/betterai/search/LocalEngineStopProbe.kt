package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRuntimeService


/**
 * The engine lease the entry checks borrow ([LocalEntryAbilityCheck], [LocalEntryCopyRisk]): one entry event read on
 * this board, no turn played out. The stop probe that played each opponent move for three turns against a sweeper is
 * gone (user decision 2026-10-19: no event-branch search); what a move does to a sweeper is read from its data
 * ([jbro.cobblemon.mcc.betterai.matchup.LocalStopScoreCalculator]).
 */
internal object LocalEngineStopProbe {
    interface Lease {
        fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T?
    }

    /** Production borrows the server's engine; tests set their own worker. */
    @Volatile
    var lease: Lease = object : Lease {
        override fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? =
            NativeShowdownRuntimeService.withWorker(deadlineNanos, action)
    }
}
