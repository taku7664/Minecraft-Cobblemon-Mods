package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning

/** Exact-input reuse, owned by one synchronous decision and released even when it fails. */
internal object LocalDecisionCalculationScope {
    internal class Identity(private val value: Any) {
        override fun equals(other: Any?): Boolean = other is Identity && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    internal class Scope {
        val values = LinkedHashMap<List<Any?>, Any>()
        var hits = 0
        var misses = 0
    }

    private val active = ThreadLocal<Scope?>()

    fun <T> with(block: (Scope) -> T): T {
        val previous = active.get()
        val scope = Scope()
        active.set(scope)
        try { return block(scope) } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    fun <T : Any> reuse(kind: String, vararg inputs: Any?, calculate: () -> T): T {
        val scope = active.get() ?: return calculate()
        val key = listOf(kind, LocalActiveTuning.current()) + inputs
        @Suppress("UNCHECKED_CAST")
        scope.values[key]?.let { scope.hits++; return it as T }
        scope.misses++
        return calculate().also {
            scope.values[key] = it
            // A deep search must not retain every visited board just for this optional reuse.
            while (scope.values.size > MAXIMUM_ENTRIES) scope.values.remove(scope.values.keys.first())
        }
    }

    private const val MAXIMUM_ENTRIES = 128
}
