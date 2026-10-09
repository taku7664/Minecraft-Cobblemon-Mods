package jbro.cobblemon.mcc.league.system

import kotlin.math.roundToInt

/**
 * A player-independent level for each square region. Random anchor heights are linearly interpolated;
 * one randomly positioned anchor per 2x2 anchor tile is always the maximum, so high areas cannot disappear
 * through bad luck. The saved spawn-origin region is always the minimum.
 */
object WildSpawnRegions {
    fun level(
        seed: Long, dimension: String, chunkX: Int, chunkZ: Int, rule: WildLevelRule,
        originChunkX: Int = 0, originChunkZ: Int = 0,
    ): Int {
        val regionX = Math.floorDiv(chunkX, rule.regionChunks) - Math.floorDiv(originChunkX, rule.regionChunks)
        val regionZ = Math.floorDiv(chunkZ, rule.regionChunks) - Math.floorDiv(originChunkZ, rule.regionChunks)
        val x0 = Math.floorDiv(regionX, rule.transitionRegions)
        val z0 = Math.floorDiv(regionZ, rule.transitionRegions)
        val tx = Math.floorMod(regionX, rule.transitionRegions).toDouble() / rule.transitionRegions
        val tz = Math.floorMod(regionZ, rule.transitionRegions).toDouble() / rule.transitionRegions
        val salt = mix(seed xor (dimension.hashCode().toLong() * DIMENSION_SALT))
        fun anchor(x: Int, z: Int): Double {
            if (x == 0 && z == 0) return rule.regionMin.toDouble()
            val tileX = Math.floorDiv(x, 2)
            val tileZ = Math.floorDiv(z, 2)
            val peakHash = mix(salt xor (tileX.toLong() * CORNER_X) xor (tileZ.toLong() * CORNER_Z) xor PEAK_SALT) ushr 1
            // The spawn tile must put its guaranteed high anchor somewhere other than the starting origin.
            val peak = if (tileX == 0 && tileZ == 0) 1 + (peakHash % 3).toInt() else (peakHash % 4).toInt()
            if (Math.floorMod(x, 2) == (peak and 1) && Math.floorMod(z, 2) == (peak ushr 1)) return rule.regionMax.toDouble()
            val random = mix(salt xor (x.toLong() * CORNER_X) xor (z.toLong() * CORNER_Z)) ushr 1
            return rule.regionMin + (random % (rule.regionMax - rule.regionMin + 1)).toDouble()
        }
        val north = lerp(anchor(x0, z0), anchor(x0 + 1, z0), tx)
        val south = lerp(anchor(x0, z0 + 1), anchor(x0 + 1, z0 + 1), tx)
        return lerp(north, south, tz).roundToInt().coerceIn(rule.regionMin, rule.regionMax)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun mix(value: Long): Long {
        var z = value + GOLDEN
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    private const val GOLDEN = -0x61c8864680b583ebL
    private const val MIX_1 = -0x40a7b892e31b1a47L
    private const val MIX_2 = -0x6b2fb644ecceee15L
    private const val DIMENSION_SALT = 0x632BE59BD9B4E019L
    private const val CORNER_X = 0x3C6EF372FE94F82BL
    private const val CORNER_Z = -0x5AB2A1F2B1B1F8D5L
    private const val PEAK_SALT = 0x12ED2D3217CBA901L
}
