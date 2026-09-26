package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView

/** Changes visit order only; no public or hypothetical opponent response is discarded. */
internal object NativeOpponentResponseOrdering {
    fun order(
        actions: List<BattleActionCandidate>,
        memory: BattleTacticalMemoryView,
        information: Double,
        previousValues: Map<String, Double> = emptyMap(),
    ): List<BattleActionCandidate> {
        if (actions.size < 2) return actions
        val learned = LocalOpponentResponseModel.distribution(actions, memory, information)
            ?.takeIf { it.influence > 0.0 }
        if (previousValues.isEmpty() && learned == null) return actions
        return actions.withIndex().sortedWith(
            compareBy<IndexedValue<BattleActionCandidate>> {
                previousValues[it.value.actionId] ?: Double.POSITIVE_INFINITY
            }.thenByDescending { learned?.weights?.get(it.value) ?: 0.0 }
                .thenBy(IndexedValue<BattleActionCandidate>::index),
        ).map(IndexedValue<BattleActionCandidate>::value)
    }
}
