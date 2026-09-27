package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Cobblemon173LeadChoiceTest {
    private val team = List(4) { UUID.nameUUIDFromBytes("lead-$it".toByteArray()) }

    @Test
    fun `the chosen lead moves to the front and everyone else keeps their order`() {
        assertEquals(listOf(team[2], team[0], team[1], team[3]), reorder(listOf(team[2]), 1))
        assertEquals(listOf(team[3], team[1], team[0], team[2]), reorder(listOf(team[3], team[1]), 2))
    }

    @Test
    fun `an answer that is not exactly the lead slots from this team is ignored`() {
        assertNull(reorder(listOf(team[0], team[1]), 1), "too many leads")
        assertNull(reorder(emptyList(), 1), "no lead")
        assertNull(reorder(listOf(team[1], team[1]), 2), "duplicate lead")
        assertNull(reorder(listOf(UUID.randomUUID()), 1), "not on this team")
    }

    private fun reorder(leads: List<UUID>, leadCount: Int) =
        Cobblemon173LeadChoice.reorder(team, { it }, leads, leadCount)
}
