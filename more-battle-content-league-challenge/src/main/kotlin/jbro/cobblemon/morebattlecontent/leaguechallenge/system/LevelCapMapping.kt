package jbro.cobblemon.morebattlecontent.leaguechallenge.system

/** Exact CLC tier mapping. Validate the whole route, not just today's cap. */
object LevelCapMapping {
    fun resolve(tiers: Map<String, Int>, requiredCaps: Set<Int>): Map<Int, Int> = requiredCaps.associateWith { cap ->
        require(cap in 1..100)
        val tier = tiers.entries.asSequence()
            .filter { it.value == cap && it.key.toIntOrNull()?.toString() == it.key }
            .mapNotNull { it.key.toIntOrNull() }.filter { it > 0 }.minOrNull()
        checkNotNull(tier) { "cap_unmapped:$cap" }
    }
}
