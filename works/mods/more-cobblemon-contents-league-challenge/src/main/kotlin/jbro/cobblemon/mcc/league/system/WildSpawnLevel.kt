package jbro.cobblemon.mcc.league.system

import kotlin.math.floor

/** Regional levels are independent of players; the cap limits only the effective level used by a new spawn. */
data class WildLevelRule(
    val belowCap: Int = 6,
    val spread: Int = 3,
    val regionChunks: Int = 4,
    val regionMin: Int = 10,
    val regionMax: Int = 83,
    val transitionRegions: Int = 16,
) {
    init {
        require(belowCap in 3..99) { "below_cap must be between 3 and 99" }
        require(spread in 0..50 && belowCap - spread >= 3) { "spread must leave at least 3 levels below the effective cap" }
        require(regionChunks in 1..64) { "region_chunks must be between 1 and 64" }
        require(regionMin in 10..100 && regionMax in regionMin..100) { "region_min and region_max must be ordered within 10..100" }
        require(transitionRegions in 1..64) { "transition_regions must be between 1 and 64" }
    }

    fun range(cap: Int, regionLevel: Int): IntRange {
        require(cap in 1..100)
        require(regionLevel in regionMin..regionMax)
        val effective = minOf(regionLevel, cap)
        val top = (effective - belowCap + spread).coerceAtLeast(1)
        val bottom = (effective - belowCap - spread).coerceIn(1, top)
        return bottom..top
    }
}

object WildSpawnLevel {
    /** Draw from a narrow band AFTER limiting the region's level, rather than clamping a wide random result. */
    fun roll(cap: Int, rule: WildLevelRule, regionLevel: Int, roll: Double): Int {
        require(roll >= 0.0 && roll < 1.0)
        val range = rule.range(cap, regionLevel)
        val offset = floor(roll * (range.last - range.first + 1)).toInt()
        return range.first + offset
    }
}
