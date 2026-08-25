package jbro.cobblemon.morebattlecontent.betterai.mechanics

/** Public, generation-stable side-condition rules shared by scoring and projection. */
internal object LocalSideConditionRules {
    fun canonical(effectId: String): String =
        effectId.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    fun maximumStacks(effectId: String, declaredMaximum: Int? = null): Int? {
        val knownMaximum = HAZARD_MAXIMUM_STACKS[canonical(effectId)]
        val declared = declaredMaximum?.coerceAtLeast(1)
        return when {
            knownMaximum != null && declared != null -> minOf(knownMaximum, declared)
            knownMaximum != null -> knownMaximum
            else -> declared
        }
    }

    fun fixedDuration(effectId: String): Int? = FIXED_DURATION_BY_ID[canonical(effectId)]

    fun isSupported(effectId: String): Boolean =
        fixedDuration(effectId) != null || maximumStacks(effectId) != null

    private val FIXED_DURATION_BY_ID = mapOf(
        "tailwind" to 4,
        "reflect" to 5,
        "lightscreen" to 5,
        "auroraveil" to 5,
        "safeguard" to 5,
        "mist" to 5,
        "luckychant" to 5,
        "firepledge" to 4,
        "grasspledge" to 4,
        "waterpledge" to 4,
        "gmaxcannonade" to 4,
        "gmaxvinelash" to 4,
        "gmaxvolcalith" to 4,
        "gmaxwildfire" to 4,
        "craftyshield" to 1,
        "matblock" to 1,
        "quickguard" to 1,
        "wideguard" to 1,
    )

    private val HAZARD_MAXIMUM_STACKS = mapOf(
        "stealthrock" to 1,
        "stickyweb" to 1,
        "spikes" to 3,
        "toxicspikes" to 2,
    )
}
