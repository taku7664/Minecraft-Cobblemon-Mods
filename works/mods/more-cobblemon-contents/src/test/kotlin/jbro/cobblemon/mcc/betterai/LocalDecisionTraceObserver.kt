package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionObservation
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionObserver

/** Receives the brain's actual result without executing any selection policy. */
internal class LocalDecisionTraceObserver : LocalDecisionObserver {
    var latest: LocalDecisionObservation? = null
        private set
    var calls: Int = 0
        private set

    override fun resolved(decision: LocalDecisionObservation) {
        latest = decision
        calls++
    }
}
