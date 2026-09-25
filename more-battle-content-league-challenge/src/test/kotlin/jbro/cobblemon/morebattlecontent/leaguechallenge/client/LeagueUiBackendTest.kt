package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LeagueUiBackendTest {
    @Test
    fun `capture backend accepts code and owo without case sensitivity`() {
        assertEquals(LeagueUiBackend.CODE, LeagueUiBackend.parseOrNull("code"))
        assertEquals(LeagueUiBackend.OWO, LeagueUiBackend.parseOrNull("OWO"))
    }

    @Test
    fun `capture backend rejects absent and unknown values`() {
        assertNull(LeagueUiBackend.parseOrNull(null))
        assertNull(LeagueUiBackend.parseOrNull("html"))
    }
}
