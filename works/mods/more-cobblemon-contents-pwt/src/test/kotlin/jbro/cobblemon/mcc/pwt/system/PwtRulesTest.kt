package jbro.cobblemon.mcc.pwt.system

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PwtRulesTest {
    private fun mon(n: Int, species: String = "cobblemon:s$n", item: String? = "cobblemon:i$n", legendary: Boolean = false) =
        PwtRules.Candidate(UUID(0, n.toLong()), species, item, legendary)

    private val party = listOf(mon(1), mon(2), mon(3), mon(4, species = "cobblemon:s1"), mon(5, item = "cobblemon:i1"),
        mon(6, legendary = true), mon(7, item = null), mon(8, item = null))

    private fun pick(vararg n: Int) = n.map { UUID(0, it.toLong()) }

    @Test
    fun `three distinct legal Pokemon enter`() {
        assertEquals(emptyList<String>(), PwtRules.violations(party, pick(1, 2, 3)))
        // Pokemon holding nothing never clash on items.
        assertEquals(emptyList<String>(), PwtRules.violations(party, pick(1, 7, 8)))
    }

    @Test
    fun `each rule names its own violation`() {
        assertEquals(listOf("team_size"), PwtRules.violations(party, pick(1, 2)))
        assertEquals(listOf("duplicate_pick"), PwtRules.violations(party + mon(9), pick(1, 1, 2)).filter { it != "team_size" })
        assertEquals(listOf("not_in_party"), PwtRules.violations(party, pick(1, 2, 99)))
        assertEquals(listOf("legendary"), PwtRules.violations(party, pick(1, 2, 6)))
        assertEquals(listOf("duplicate_species"), PwtRules.violations(party, pick(1, 2, 4)))
        assertEquals(listOf("duplicate_item"), PwtRules.violations(party, pick(1, 2, 5)))
    }
}
