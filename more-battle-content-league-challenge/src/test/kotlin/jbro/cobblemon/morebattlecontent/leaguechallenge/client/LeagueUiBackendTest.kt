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

    @Test
    fun `capture locale accepts both bundled languages without case sensitivity`() {
        assertEquals(LeagueUiLocale.EN_US, LeagueUiLocale.parseOrNull("en_us"))
        assertEquals(LeagueUiLocale.KO_KR, LeagueUiLocale.parseOrNull("KO_KR"))
    }

    @Test
    fun `capture locale rejects absent and unsupported values`() {
        assertNull(LeagueUiLocale.parseOrNull(null))
        assertNull(LeagueUiLocale.parseOrNull("ja_jp"))
    }
}
