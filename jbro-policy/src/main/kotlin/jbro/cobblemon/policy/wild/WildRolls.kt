package jbro.cobblemon.policy.wild

import jbro.cobblemon.policy.config.IvRange

/** The random choices of the wild policy, given their random numbers. */
internal object WildRolls {
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
