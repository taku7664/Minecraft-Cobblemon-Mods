package jbro.cobblemon.mcc.internal.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * The canonical form of a public id (`"cobblemon:Trick Room"` -> `"trickroom"`): the part after the
 * namespace, lowercased, letters and digits only.
 *
 * The public mechanics compare ids this way at every search node, and building the string again each
 * time was a third of a Boss doubles decision's CPU time. Ids come from a small, fixed vocabulary
 * (moves, abilities, items, effects), so the results are cached; the cap only guards against an
 * unexpected stream of distinct strings.
 */
object PublicIds {
    private const val CACHE_LIMIT = 65_536
    private val cache = ConcurrentHashMap<String, String>()

    fun canonical(value: String): String {
        cache[value]?.let { return it }
        val computed = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
        if (cache.size < CACHE_LIMIT) cache.putIfAbsent(value, computed)
        return computed
    }
}
