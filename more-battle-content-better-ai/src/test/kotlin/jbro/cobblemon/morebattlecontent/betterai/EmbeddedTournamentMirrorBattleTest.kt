package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class EmbeddedTournamentMirrorBattleTest {
    @Test
    fun `world cup team is complete and mirrored without sharing mutable json`() {
        val pair = EmbeddedTournamentMirrorBattle.pair(leadIndex = 0, seedIndex = 0)
        val p1 = pair.getAsJsonObject("p1")
        val p2 = pair.getAsJsonObject("p2")

        assertEquals(p1, p2)
        assertNotSame(p1, p2)
        assertEquals(6, p1.getAsJsonArray("sets").size())
        p1.getAsJsonArray("sets").forEach { set ->
            assertEquals(4, set.asJsonObject.getAsJsonArray("moves").size())
            assertEquals("M", set.asJsonObject["gender"].asString)
        }
    }

    @Test
    fun `each team member receives exactly one mirror lead rotation`() {
        val leads = (0 until 6).map { lead ->
            EmbeddedTournamentMirrorBattle.pair(lead, seedIndex = 0)
                .getAsJsonObject("p1")
                .getAsJsonArray("sets")[0]
                .asJsonObject["species"]
                .asString
        }

        assertEquals(
            listOf("Kingambit", "Skarmory", "Garganacl", "Gliscor", "Slowking-Galar", "Dragapult"),
            leads,
        )
    }
}
