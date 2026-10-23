package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.policy.*

/** Explicit diagnostic policy override, independent of observing the resulting decision. */
internal class LocalSeedOverrideActionSelector(private val seed: Long) : LocalActionSelector {
    private val delegate = LocalWeightedActionSelector()

    override fun choose(ranked: List<LocalBattleActionRank>, seed: Long,
        context: LocalActionMixingContext): LocalActionSelection = delegate.choose(ranked, this.seed, context)
}
