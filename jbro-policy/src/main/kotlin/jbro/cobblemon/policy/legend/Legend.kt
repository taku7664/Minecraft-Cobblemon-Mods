package jbro.cobblemon.policy.legend

enum class LegendTier { LEGENDARY, MYTHICAL, RESTRICTED, PARADOX }

/** The League rank needed to catch a Legend, by LeagueRank name; kept apart so it loads without League Challenge. */
enum class LegendRank { ULTRA_BALL, MASTER_BALL, CHAMPION }

/**
 * One wild Legend. [entry] lists species the spawning player must carry in the party for it to appear: one of them,
 * or all of them when [entryAll].
 */
data class Legend(
    val species: String,
    val tier: LegendTier,
    val rank: LegendRank,
    val entry: List<String> = emptyList(),
    val entryAll: Boolean = false,
) {
    /** [party] holds the species ids in the player's party. */
    fun entryMet(party: Set<String>): Boolean = when {
        entry.isEmpty() -> true
        entryAll -> party.containsAll(entry)
        else -> entry.any { it in party }
    }
}
