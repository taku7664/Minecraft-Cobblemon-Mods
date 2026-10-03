package jbro.cobblemon.mcc.internal.compat.cobblemon173

/** Inactive request slots retain their positions in a joint Showdown choice. */
internal object Cobblemon173RequestSlotRules {
    fun mustPass(switchRequest: Boolean, forced: Boolean, alive: Boolean, commanding: Boolean): Boolean =
        if (switchRequest) !forced else !alive || commanding

    fun eligibleReplacement(reviving: Boolean, alive: Boolean, active: Boolean): Boolean =
        if (reviving) !alive else alive && !active

    fun replacementCount(forcedSlots: Int, healthyBench: Int): Int = minOf(forcedSlots, healthyBench)
}
