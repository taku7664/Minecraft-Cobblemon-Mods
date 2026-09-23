package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class EmbeddedBssTournamentMirrorBattleTest {
    @Test
    fun `published bss entry is level 50 and respects species and item clauses`() {
        val entry = EmbeddedBssTournamentMirrorBattle.entry()
        val sets = entry.getAsJsonArray("sets").map { it.asJsonObject }

        assertEquals(6, sets.size)
        assertEquals(6, sets.map { it["species"].asString }.distinct().size)
        assertEquals(6, sets.map { it["item"].asString }.distinct().size)
        sets.forEach { set ->
            assertEquals(50, set["level"].asInt)
            assertEquals(4, set.getAsJsonArray("moves").size())
        }
    }

    @Test
    fun `mirror battle brings exactly the published three without sharing json`() {
        val pair = EmbeddedBssTournamentMirrorBattle.pair(leadIndex = 0, seedIndex = 0)
        val p1 = pair.getAsJsonObject("p1")
        val p2 = pair.getAsJsonObject("p2")

        assertEquals(p1, p2)
        assertNotSame(p1, p2)
        assertEquals(
            listOf("Koraidon", "Calyrex-Shadow", "Flutter Mane"),
            p1.getAsJsonArray("sets").map { it.asJsonObject["species"].asString },
        )
    }

    @Test
    fun `selected three each receive one lead rotation`() {
        val leads = (0 until 3).map { lead ->
            EmbeddedBssTournamentMirrorBattle.pair(lead, seedIndex = 0)
                .getAsJsonObject("p1")
                .getAsJsonArray("sets")[0]
                .asJsonObject["species"]
                .asString
        }

        assertEquals(listOf("Koraidon", "Calyrex-Shadow", "Flutter Mane"), leads)
    }
}
