package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Locale

/** Public, deterministic STAB rules shared by inference and local fallback evaluation. */
object BattlePublicStabRules {
    /**
     * Returns null when the public base typing is unavailable. For Stellar, a null consumed-type set
     * means observation cannot prove whether the one-use boost remains, so the safe lower bound is
     * returned instead of manufacturing a fresh boost on every simulated turn.
     */
    @JvmStatic
    fun conservativeMultiplier(
        baseTypeIds: Set<String>,
        teraTypeId: String?,
        stellarBoostedTypeIds: Set<String>?,
        moveTypeId: String,
    ): Double? {
        val baseTypes = baseTypeIds.mapTo(linkedSetOf(), ::canonical)
        if (baseTypes.isEmpty()) return null
        val moveType = canonical(moveTypeId)
        val teraType = teraTypeId?.let(::canonical)
        val matchesBase = moveType in baseTypes

        if (teraType == STELLAR) {
            val consumed = stellarBoostedTypeIds?.mapTo(linkedSetOf(), ::canonical)
                ?: return if (matchesBase) ORDINARY_STAB else NO_STAB
            if (moveType !in consumed) {
                return if (matchesBase) STELLAR_BASE_FIRST_USE else STELLAR_OTHER_FIRST_USE
            }
            return if (matchesBase) ORDINARY_STAB else NO_STAB
        }

        val matchesTera = teraType != null && teraType == moveType
        return when {
            matchesBase && matchesTera -> MATCHING_TERA_STAB
            matchesBase || matchesTera -> ORDINARY_STAB
            else -> NO_STAB
        }
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val STELLAR = "stellar"
    private const val NO_STAB = 1.0
    private const val ORDINARY_STAB = 1.5
    private const val MATCHING_TERA_STAB = 2.0
    private const val STELLAR_BASE_FIRST_USE = 2.0
    private const val STELLAR_OTHER_FIRST_USE = 4915.0 / 4096.0
}
