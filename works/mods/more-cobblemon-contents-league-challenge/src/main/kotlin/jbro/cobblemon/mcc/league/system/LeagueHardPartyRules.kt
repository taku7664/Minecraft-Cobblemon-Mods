package jbro.cobblemon.mcc.league.system

/**
 * The hard League's team rules, as in the games' Battle Facilities: no two party members of the same species
 * (regional and other forms count as one species) and no two holding the same item. Members without an item are free.
 */
object LeagueHardPartyRules {
    /** One party member as the rule reads it: its species ID and its held item ID, null when it holds nothing. */
    data class Member(val speciesId: String, val heldItemId: String?)

    /** The error key the challenge is refused with, or null when the party may enter. */
    fun violation(party: List<Member>): String? {
        if (party.map(Member::speciesId).distinct().size != party.size) return "duplicate_species"
        val items = party.mapNotNull(Member::heldItemId)
        if (items.distinct().size != items.size) return "duplicate_item"
        return null
    }
}
