package jbro.cobblemon.mcc.internal.ai

import java.util.Collections

/**
 * A view's own copy of a set: made once from the caller's collection, never exposed for writing. A view
 * built from another view's frozen set takes it as it is, since nothing can change it; the search builds
 * views by the million, and copying and re-validating the same collections was a large share of it.
 *
 * Writes throw [UnsupportedOperationException], as the unmodifiable wrappers it replaces did, even
 * through a cast to a mutable type.
 */
internal class FrozenSet<T> private constructor(source: Set<T>) : java.util.AbstractSet<T>() {
    private val items: Set<T> = Collections.unmodifiableSet(LinkedHashSet(source))

    override val size: Int get() = items.size
    override fun contains(element: T): Boolean = items.contains(element)
    override fun iterator(): MutableIterator<T> = (items as MutableSet<T>).iterator()

    companion object {
        private val EMPTY = FrozenSet<Any?>(emptySet())

        @Suppress("UNCHECKED_CAST")
        fun <T> of(source: Set<T>): Set<T> = when {
            source is FrozenSet<*> -> source
            source.isEmpty() -> EMPTY as Set<T>
            else -> FrozenSet(source)
        }
    }
}

/** The map counterpart of [FrozenSet]. */
internal class FrozenMap<K, V> private constructor(source: Map<K, V>) : java.util.AbstractMap<K, V>() {
    private val items: Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = (items as MutableMap<K, V>).entries
    override val size: Int get() = items.size
    override fun get(key: K): V? = items[key]
    override fun containsKey(key: K): Boolean = items.containsKey(key)

    companion object {
        private val EMPTY = FrozenMap<Any?, Any?>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        fun <K, V> of(source: Map<K, V>): Map<K, V> = when {
            source is FrozenMap<*, *> -> source
            source.isEmpty() -> EMPTY as Map<K, V>
            else -> FrozenMap(source)
        }
    }
}
