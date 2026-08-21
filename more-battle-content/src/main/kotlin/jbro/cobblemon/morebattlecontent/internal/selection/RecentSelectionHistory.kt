package jbro.cobblemon.morebattlecontent.internal.selection

import java.util.Collections

internal class RecentSelectionHistory<K, V>(private val capacity: Int) {
    private val selections = HashMap<K, ArrayDeque<V>>()

    init {
        require(capacity > 0) { "Recent selection history capacity must be positive" }
    }

    @Synchronized
    fun recent(owner: K): Set<V> = Collections.unmodifiableSet(
        LinkedHashSet(selections[owner].orEmpty()),
    )

    @Synchronized
    fun record(owner: K, selection: V) {
        val recent = selections.getOrPut(owner, ::ArrayDeque)
        recent.remove(selection)
        recent.addLast(selection)
        while (recent.size > capacity) recent.removeFirst()
    }

    @Synchronized
    fun forget(owner: K) {
        selections.remove(owner)
    }
}
