package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Remembers one derived value for the last battle state each thread asked about. States are immutable
 * and the search asks the same board-wide question (is Magic Room up, does anyone's Neutralizing Gas
 * work, which weather counts) many times in a row about one state, so a single slot per thread catches
 * almost every repeat without keeping states alive or sharing anything between search threads.
 */
internal class LocalStateMemo<T>(private val compute: (BattleStateView) -> T) {
    private class Slot<T>(val state: BattleStateView, val value: T)

    private val last = ThreadLocal<Slot<T>?>()

    operator fun get(state: BattleStateView): T {
        last.get()?.let { if (it.state === state) return it.value }
        val value = compute(state)
        last.set(Slot(state, value))
        return value
    }
}
