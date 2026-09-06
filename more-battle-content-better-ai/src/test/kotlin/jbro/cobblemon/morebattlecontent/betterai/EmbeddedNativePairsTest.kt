package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedNativePairsTest {
    private fun pair() = JsonParser.parseString("""{"battleSeed":[1,2,3,4],"p1":{"setIds":["A"],"sets":[{"species":"Pikachu"}]},"p2":{"setIds":["B"],"sets":[{"species":"Blissey"}]}}""").asJsonObject
    private fun result(status: String, winner: String?) = JsonObject().apply {
        addProperty("status", status); addProperty("winner", winner)
    }

    @Test
    fun `orientation swaps whole teams without mutating input or battle seed`() {
        val original = pair()
        val saved = original.deepCopy()
        val reverse = EmbeddedNativePairs.orient(original, true)
        assertEquals(original["p1"], reverse["p2"])
        assertEquals(original["p2"], reverse["p1"])
        assertEquals(original["battleSeed"], reverse["battleSeed"])
        reverse.getAsJsonObject("p1").addProperty("changed", true)
        assertEquals(saved, original)
        assertEquals(original, EmbeddedNativePairs.orient(original, false))
    }

    @Test
    fun `team A wins in either seat while censored games are not draws`() {
        assertEquals("WIN", EmbeddedNativePairs.outcome(result("COMPLETE", "p1"), false))
        assertEquals("WIN", EmbeddedNativePairs.outcome(result("COMPLETE", "p2"), true))
        assertEquals("LOSS", EmbeddedNativePairs.outcome(result("COMPLETE", "p1"), true))
        assertEquals("DRAW", EmbeddedNativePairs.outcome(result("COMPLETE", null), false))
        assertEquals("INCOMPLETE", EmbeddedNativePairs.outcome(result("TURN_LIMIT", null), true))
        assertThrows(IllegalArgumentException::class.java) { EmbeddedNativePairs.outcome(result("WAITING", null), false) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedNativePairs.outcome(result("COMPLETE", "unknown"), true) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedNativePairs.outcome(result("TURN_LIMIT", "p1"), false) }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedNativePairs.outcome(result("COMPLETE", null).apply { remove("winner") }, false)
        }
    }
}
