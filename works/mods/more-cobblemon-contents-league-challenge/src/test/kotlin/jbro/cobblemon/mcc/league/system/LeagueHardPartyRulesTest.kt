package jbro.cobblemon.mcc.league.system

import jbro.cobblemon.mcc.league.system.LeagueHardPartyRules.Member
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class LeagueHardPartyRulesTest {
    @Test
    fun `a party of different species and items enters, and empty hands never clash`() {
        assertNull(LeagueHardPartyRules.violation(listOf(
            Member("cobblemon:garchomp", "cobblemon:choice_scarf"),
            Member("cobblemon:rotom", null),
            Member("cobblemon:togekiss", null),
            Member("cobblemon:ferrothorn", "cobblemon:leftovers"),
        )))
    }

    @Test
    fun `the same species twice is refused, whatever the held items`() {
        assertEquals("duplicate_species", LeagueHardPartyRules.violation(listOf(
            Member("cobblemon:ninetales", "cobblemon:heat_rock"),
            Member("cobblemon:ninetales", "cobblemon:light_clay"),
        )))
    }

    @Test
    fun `the same held item twice is refused`() {
        assertEquals("duplicate_item", LeagueHardPartyRules.violation(listOf(
            Member("cobblemon:garchomp", "cobblemon:leftovers"),
            Member("cobblemon:ferrothorn", "cobblemon:leftovers"),
        )))
    }
}
