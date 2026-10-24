package jbro.cobblemon.mcc.betterai.brain

/**
 * The inner values one decision computes, kept for its record ([LocalDecisionStages.internals]): what a formula read and
 * what it gave, so a recorded choice can be checked against the arithmetic behind it without replaying it. Each entry is
 * one reading (a Pokemon's worth, a knockout's price, a heal's value), its variables by name. Per decision and per thread,
 * as [jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth]'s reading is.
 */
internal object LocalDecisionInternals {
    private val values = ThreadLocal<LinkedHashMap<String, Map<String, Double>>?>()

    internal data class Recorded<T>(val value: T, val entries: List<Pair<String, Map<String, Double>>>)
    private val recorder = ThreadLocal<MutableList<Pair<String, Map<String, Double>>>?>()

    /** Capture writes, including nested calculators, so reuse can replay the same decision evidence. */
    internal fun <T> capture(block: () -> T): Recorded<T> {
        val previous = recorder.get()
        val entries = ArrayList<Pair<String, Map<String, Double>>>()
        recorder.set(entries)
        try { return Recorded(block(), entries) } finally {
            if (previous == null) recorder.remove() else recorder.set(previous)
        }
    }

    fun begin() = values.set(LinkedHashMap())

    /** Kept only while a decision is being read; the last reading under [key] stands. */
    fun record(key: String, variables: Map<String, Double>) {
        val sink = recorder.get()
        if (sink != null) sink.add(key to variables) else values.get()?.put(key, variables)
    }

    fun take(): Map<String, Map<String, Double>> = values.get()?.let { LinkedHashMap(it) }.orEmpty()

    fun clear() = values.remove()

    fun flag(value: Boolean) = if (value) 1.0 else 0.0
}
