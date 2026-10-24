package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Remembers one derived value for the last battle state asked about. States are immutable and the search asks the same
 * board-wide question (is Magic Room up, does anyone's Neutralizing Gas work, which weather counts) many times in a row
 * about one state, so a single slot catches almost every repeat without keeping more than one state alive.
 *
 * The slot is one shared reference, not one per thread: the value is a function of the state alone, so two threads
 * taking turns at it only recompute, never read a wrong value, and a read is a plain field load where a ThreadLocal
 * lookup cost 5% of a decision (2026-10-20 profile).
 */
internal class LocalStateMemo<T>(private val compute: (BattleStateView) -> T) {
    private class Slot<T>(val state: BattleStateView, val value: T)

    @Volatile private var last: Slot<T>? = null

    operator fun get(state: BattleStateView): T {
        last?.let { if (it.state === state) return it.value }
        val value = compute(state)
        val slot = Slot(state, value)
        last = slot
        // Do not erase a newer reading installed by a different decision/thread.
        jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.onClose(this) {
            if (last === slot) last = null
        }
        return value
    }
}
