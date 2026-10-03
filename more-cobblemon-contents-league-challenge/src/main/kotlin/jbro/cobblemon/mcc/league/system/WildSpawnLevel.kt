package jbro.cobblemon.mcc.league.system

import kotlin.math.exp
import kotlin.math.floor

/**
 * Where wild Pokemon levels sit under a player's cap. The top of the range is `cap - belowCap + spread`; the bottom is
 * `cap - belowCap - spread`, or [floorLevel] once the cap is high enough to reach past it, so a high cap still brings
 * low-level Pokemon. Spawns never go above the cap (so they can always be caught) nor below level 1. Areas of
 * [regionChunks] by [regionChunks] chunks lean weak or strong, which only makes the low or the high end of that range
 * more likely there.
 */
data class WildLevelRule(
    val belowCap: Int = 10,
    val spread: Int = 7,
    val regionChunks: Int = 4,
    val floorLevel: Int = 10,
) {
    init {
        require(belowCap in 0..99) { "below_cap must be between 0 and 99" }
        require(spread in 0..50) { "spread must be between 0 and 50" }
        require(regionChunks in 1..64) { "region_chunks must be between 1 and 64" }
        require(floorLevel in 1..100) { "floor_level must be between 1 and 100" }
    }

    /** The levels a spawn under [cap] can take. */
    fun range(cap: Int): IntRange {
        val top = (cap - belowCap + spread).coerceIn(1, cap)
        val bottom = minOf(floorLevel, cap - belowCap - spread).coerceIn(1, top)
        return bottom..top
    }
}

object WildSpawnLevel {
    /** How strongly a full lean favours its end: the far end of the range becomes e^(2 * LEAN_STRENGTH) times likelier. */
    private const val LEAN_STRENGTH = 2.0

    /**
     * A level for a spawn under [cap] in an area leaning [lean] (-1 weakest, 0 even, 1 strongest), drawn with [roll]
     * in [0, 1).
     */
    fun roll(cap: Int, rule: WildLevelRule, lean: Double, roll: Double): Int {
        require(cap in 1..100)
        require(lean in -1.0..1.0)
        require(roll >= 0.0 && roll < 1.0)
        val levels = rule.range(cap).toList()
        val half = (levels.size - 1) / 2.0
        // Each level's place in the range from -1 (bottom) to 1 (top), so a lean tilts any width alike.
        val weights = levels.indices.map { if (half == 0.0) 1.0 else exp(LEAN_STRENGTH * lean * (it - half) / half) }
        var target = roll * weights.sum()
        for (index in levels.indices) {
            target -= weights[index]
            if (target < 0) return levels[index]
        }
        return levels.last()
    }
}

/**
 * A smooth weak-or-strong lean per chunk: value noise over square regions of chunks. Each region corner takes a
 * value hashed from the world seed, the dimension and the corner, and a chunk blends its four nearest corners, so
 * neighbouring chunks differ a little and the lean drifts across a few regions. Nothing is stored; the same world
 * always gives the same lean.
 */
object WildSpawnRegions {
    fun lean(seed: Long, dimension: String, chunkX: Int, chunkZ: Int, regionChunks: Int): Double {
        require(regionChunks >= 1)
        // Chunk centres, so a whole chunk shares one lean.
        val gx = (chunkX + 0.5) / regionChunks
        val gz = (chunkZ + 0.5) / regionChunks
        val x0 = floor(gx).toInt()
        val z0 = floor(gz).toInt()
        // Plain linear blending: corners differ by at most 2, so one chunk over moves the lean by at most
        // 2 / regionChunks. A smoothstep would look softer on a map but steepen the middle of each region.
        val tx = gx - x0
        val tz = gz - z0
        val salt = mix(seed xor (dimension.hashCode().toLong() * DIMENSION_SALT))
        fun corner(x: Int, z: Int) = unit(mix(salt xor (x.toLong() * CORNER_X) xor (z.toLong() * CORNER_Z)))
        val north = lerp(corner(x0, z0), corner(x0 + 1, z0), tx)
        val south = lerp(corner(x0, z0 + 1), corner(x0 + 1, z0 + 1), tx)
        return lerp(north, south, tz).coerceIn(-1.0, 1.0)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** SplitMix64's finaliser, so nearby inputs give unrelated outputs. */
    private fun mix(value: Long): Long {
        var z = value + GOLDEN
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** The top 53 bits as a double in [-1, 1). */
    private fun unit(bits: Long): Double = (bits ushr 11) * (2.0 / (1L shl 53)) - 1.0

    private const val GOLDEN = -0x61c8864680b583ebL
    private const val MIX_1 = -0x40a7b892e31b1a47L
    private const val MIX_2 = -0x6b2fb644ecceee15L
    private const val DIMENSION_SALT = 0x632BE59BD9B4E019L
    private const val CORNER_X = 0x3C6EF372FE94F82BL
    private const val CORNER_Z = -0x5AB2A1F2B1B1F8D5L
}
