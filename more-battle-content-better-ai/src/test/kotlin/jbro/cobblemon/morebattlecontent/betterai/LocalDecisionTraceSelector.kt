package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.policy.*

/** Test-only observation at the real Brain's final selector boundary, after mind and root refinement. */
internal class LocalDecisionTraceSelector : LocalActionSelector {
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
        val selected = delegate.choose(ranked, seed, context)
        latest = Trace(ranked.toList(), seed, context, selected)
        return selected
    }
}
