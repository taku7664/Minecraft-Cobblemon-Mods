package jbro.cobblemon.mcc.internal.ai

/**
 * A view's own copy of a set: made once from the caller's collection, never exposed for writing. A view
 * built from another view's frozen set takes it as it is, since nothing can change it; the search builds
 * views by the million, and copying and re-validating the same collections was a large share of it.
 */
internal class FrozenSet<T> private constructor(private val items: Set<T>) : Set<T> by items {
    override fun equals(other: Any?): Boolean = items == other
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = items.toString()

    companion object {
        private val EMPTY = FrozenSet<Any?>(emptySet())

        @Suppress("UNCHECKED_CAST")
        fun <T> of(source: Set<T>): Set<T> = when {
            source is FrozenSet<*> -> source
            source.isEmpty() -> EMPTY as Set<T>
            else -> FrozenSet(LinkedHashSet(source))
        }
    }
}

/** The map counterpart of [FrozenSet]. */
internal class FrozenMap<K, V> private constructor(private val items: Map<K, V>) : Map<K, V> by items {
    override fun equals(other: Any?): Boolean = items == other
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = items.toString()

    companion object {
        private val EMPTY = FrozenMap<Any?, Any?>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        fun <K, V> of(source: Map<K, V>): Map<K, V> = when {
            source is FrozenMap<*, *> -> source
            source.isEmpty() -> EMPTY as Map<K, V>
            else -> FrozenMap(LinkedHashMap(source))
        }
    }
}
