package jbro.cobblemon.mcc.betterai.brain

import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank

/** Diagnostic output from a completed decision; observing never chooses or re-ranks an action. */
internal fun interface LocalDecisionObserver {
    fun resolved(decision: LocalDecisionObservation)
}

internal data class LocalDecisionObservation(
    val mode: String,
    val ranked: List<LocalBattleActionRank>,
    /** The seed derived by the brain, before an explicitly injected diagnostic selector overrides it. */
    val seed: Long,
    /** Null for decisions that did not need a selector. */
    val mixing: LocalActionMixingContext?,
    val selection: LocalActionSelection,
    /** The submitted action, including any mechanic-twin resolution after selection. */
    val selected: LocalBattleActionRank,
)
