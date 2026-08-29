package kr.parkjh.pokefusion

class EvolutionFamilyIndex<T> {
    @Volatile
    private var graph: Map<T, Set<T>> = emptyMap()

    @Synchronized
    fun replace(edges: Iterable<Pair<T, T>>) {
        val replacement = mutableMapOf<T, MutableSet<T>>()
        for ((first, second) in edges) {
            replacement.getOrPut(first, ::mutableSetOf).add(second)
            replacement.getOrPut(second, ::mutableSetOf).add(first)
        }
        graph = replacement.mapValues { (_, neighbors) -> neighbors.toSet() }
    }

    fun connected(first: T, second: T): Boolean {
        if (first == second) return true
        val snapshot = graph
        val visited = mutableSetOf(first)
        val queue = ArrayDeque<T>()
        queue.add(first)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in snapshot[current].orEmpty()) {
                if (neighbor == second) return true
                if (visited.add(neighbor)) queue.add(neighbor)
            }
        }
        return false
    }
}
