package jbro.cobblemon.policy.support

import jbro.cobblemon.policy.support.DiscordAdminAccess.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiscordAdminAccessTest {
    private val settings = DiscordSettings.parse(
        """{"botToken": "T", "adminChannelId": "100", "adminAccess": {"1": ["*"], "2": ["announce", "players"], "900": ["bp"]}}""",
    )

    private fun caller(user: String, channel: String = "100", roles: List<String> = emptyList()) = DiscordCaller(user, "u$user", roles, channel)

    @Test
    fun `settings read the admin channel and who may run what`() {
        assertEquals("100", settings.adminChannelId)
        assertEquals(setOf("announce", "players"), settings.adminAccess["2"])
        assertTrue(DiscordSettings.parse("""{"botToken": "T"}""").adminAccess.isEmpty())
        assertThrows<IllegalArgumentException> { DiscordSettings.parse("""{"adminAccess": {"@운영진": ["*"]}}""") }
    }

    @Test
    fun `a star lets a user run everything, a list only what it names`() {
        assertEquals(Verdict.Allowed, DiscordAdminAccess.check(settings, caller("1"), "console"))
        assertEquals(Verdict.Allowed, DiscordAdminAccess.check(settings, caller("2"), "announce"))
        assertTrue(DiscordAdminAccess.check(settings, caller("2"), "ban") is Verdict.Refused)
        assertTrue(DiscordAdminAccess.check(settings, caller("3"), "players") is Verdict.Refused)
    }

    @Test
    fun `a role lets its members in`() {
        assertEquals(Verdict.Allowed, DiscordAdminAccess.check(settings, caller("3", roles = listOf("900")), "bp"))
        assertTrue(DiscordAdminAccess.check(settings, caller("3", roles = listOf("900")), "give") is Verdict.Refused)
    }

    @Test
    fun `nothing runs outside the admin channel or without one`() {
        assertTrue(DiscordAdminAccess.check(settings, caller("1", channel = "200"), "players") is Verdict.Refused)
        assertTrue(DiscordAdminAccess.check(settings.copy(adminChannelId = ""), caller("1"), "players") is Verdict.Refused)
    }
}
