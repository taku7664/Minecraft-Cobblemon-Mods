package jbro.cobblemon.mcc.league.system

/** Exact CLC tier mapping. Validate the whole route, not just today's cap. */
object LevelCapMapping {
    fun level(tiers: Map<String, Int>, tier: Int): Int = tiers[tier.toString()]
        ?: if (tier == 0) 0 else error("cap_unmapped:$tier")

    fun nonDecreasingTier(tiers: Map<String, Int>, currentTier: Int, earnedCap: Int): Int =
        if (level(tiers, currentTier) >= earnedCap) currentTier else resolve(tiers, setOf(earnedCap)).getValue(earnedCap)

    fun resolve(tiers: Map<String, Int>, requiredCaps: Set<Int>): Map<Int, Int> = requiredCaps.associateWith { cap ->
        require(cap in 1..100)
        val tier = tiers.entries.asSequence()
            .filter { it.value == cap && it.key.toIntOrNull()?.toString() == it.key }
            .mapNotNull { it.key.toIntOrNull() }.filter { it > 0 }.minOrNull()
        checkNotNull(tier) { "cap_unmapped:$cap" }
    }
}
