package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.policy.*

/** Test-only final selector observation; an explicit override isolates score-dependent random seeds. */
internal class LocalDecisionTraceSelector(private val choiceSeedOverride: Long? = null) : LocalActionSelector {
    data class Trace(
        val ranked: List<LocalBattleActionRank>,
        val seed: Long,
        val mixing: LocalActionMixingContext,
        val selection: LocalActionSelection,
    )

    private val delegate = LocalWeightedActionSelector()
    var latest: Trace? = null
        private set

    override fun choose(ranked: List<LocalBattleActionRank>, seed: Long,
        context: LocalActionMixingContext): LocalActionSelection {
        val selected = delegate.choose(ranked, choiceSeedOverride ?: seed, context)
        latest = Trace(ranked.toList(), seed, context, selected)
        return selected
    }
}
