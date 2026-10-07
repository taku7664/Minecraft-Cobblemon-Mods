package jbro.cobblemon.policy.wild

import jbro.cobblemon.policy.config.IvRange
import kotlin.random.Random

/** The random choices of the wild policy, given their random numbers. */
internal object WildRolls {
    /** Raises only enough distinct stats to guarantee two perfect IVs; higher counts are preserved. */
    fun alphaIvs(ivs: List<Int>, random: Random): List<Int> {
        val missing = 2 - ivs.count { it == 31 }
        if (missing <= 0) return ivs
        val result = ivs.toMutableList()
        ivs.indices.filter { ivs[it] != 31 }.shuffled(random).take(missing).forEach { result[it] = 31 }
        return result
    }

    /** [roll] is uniform in 0 until 100. */
    fun hiddenAbility(ratePercent: Int, roll: Int): Boolean = roll < ratePercent

    /** [point] is uniform in 0 until the summed chances. */
    fun ivRange(ranges: List<IvRange>, point: Double): IvRange {
        var remaining = point
        for (range in ranges) {
            if (remaining < range.chance) return range
            remaining -= range.chance
        }
        return ranges.last()
    }
}
