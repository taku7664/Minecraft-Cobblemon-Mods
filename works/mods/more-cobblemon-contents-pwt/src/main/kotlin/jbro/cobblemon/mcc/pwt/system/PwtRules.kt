package jbro.cobblemon.mcc.pwt.system

import java.util.UUID

/** The tournament's fixed rules, as in the original World Tournament's Champions bracket. */
object PwtRules {
    /** Pokémon each entrant brings, kept for the whole tournament. */
    const val TEAM_SIZE = 3
    /** Battles run at this level; a Pokémon below it keeps its own level. */
    const val BATTLE_LEVEL = 50
    /** Battle points for winning the tournament; nothing for any other finish. */
    const val WINNER_BP = 25L
    /** AI skill per round (MCC's 0-5 scale): the quarterfinal Advanced, the semifinal and final Boss. */
    val ROUND_SKILLS = listOf(4, 5, 5)

    /** One party Pokémon offered for entry. */
    data class Candidate(val id: UUID, val speciesId: String, val heldItemId: String?, val legendaryClass: Boolean)

    /** Why a pick of party Pokémon cannot enter, as translation key suffixes; empty when it can. */
    fun violations(party: List<Candidate>, picked: List<UUID>): List<String> = buildList {
        if (picked.size != TEAM_SIZE) add("team_size")
        if (picked.distinct().size != picked.size) add("duplicate_pick")
        val byId = party.associateBy { it.id }
        val members = picked.distinct().mapNotNull(byId::get)
        if (members.size != picked.distinct().size) add("not_in_party")
        if (members.any { it.legendaryClass }) add("legendary")
        if (members.map { it.speciesId }.distinct().size != members.size) add("duplicate_species")
        val items = members.mapNotNull { it.heldItemId }
        if (items.distinct().size != items.size) add("duplicate_item")
    }
}
