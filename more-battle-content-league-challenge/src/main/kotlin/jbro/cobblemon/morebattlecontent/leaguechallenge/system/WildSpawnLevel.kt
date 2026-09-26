package jbro.cobblemon.morebattlecontent.leaguechallenge.system

/** CLC's catching cap is the ceiling; ordinary wild spawns may be up to ten levels below it. */
object WildSpawnLevel {
    fun fromReduction(cap: Int, reduction: Int): Int {
        require(cap in 1..100)
        require(reduction in 0..10)
        return (cap - reduction).coerceAtLeast(1)
    }
}
