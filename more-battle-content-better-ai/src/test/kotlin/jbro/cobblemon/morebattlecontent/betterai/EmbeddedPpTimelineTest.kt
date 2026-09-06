package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedPpTimelineTest {
    @Test
    fun `native PP updates distinguish ordinary turns mid turn switches and transformed slots`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.ppTimeline(directory)
        val cases = result.getAsJsonArray("cases").associate { it.asJsonObject["id"].asString to it.asJsonObject }
        assertEquals(setOf("ordinary", "pivot", "transform", "pressure", "spite", "leppa", "sleeptalk",
            "fly", "fly_pressure", "outrage", "outrage_pressure", "transform_lifecycle",
            "fly_last_pp", "fly_pressure_last_pp", "encore_empty", "encore_available"), cases.keys)
        for (id in listOf("fly_last_pp", "fly_pressure_last_pp")) {
            val sample = cases.getValue(id)
            assertEquals(listOf(0, 0), sample.getAsJsonArray("ppByTurn").map { it.asInt }, id)
            assertTrue(sample.getAsJsonArray("publicLog").any {
                it.asString.startsWith("|move|") && it.asString.contains("|Fly|") && it.asString.contains("[from]lockedmove")
            }, id)
        }
        for ((id, expectedMove) in mapOf("encore_empty" to "Splash", "encore_available" to "Tackle")) {
            val sample = cases.getValue(id)
            assertEquals(listOf(expectedMove), sample.getAsJsonArray("secondTurnMoves").map { it.asString }, id)
            assertEquals(id == "encore_available", sample["encoreStarted"].asBoolean, id)
            assertEquals(0, sample["tacklePp"].asInt, id)
        }
        val lifecycle = cases.getValue("transform_lifecycle").getAsJsonArray("snapshots").map { it.asJsonObject }
        assertEquals(listOf("copied", "used", "benched", "returned"), lifecycle.map { it["phase"].asString })
        assertEquals(listOf("splash", "splash", "transform", "transform"), lifecycle.map { it["move"].asString })
        assertEquals(listOf(5, 4, 9, 9), lifecycle.map { it["pp"].asInt })
        assertEquals(listOf(true, true, false, false), lifecycle.map { it["transformed"].asBoolean })
        assertEquals(listOf(true, true, false, true), lifecycle.map { it["active"].asBoolean })
        for ((id, pp) in mapOf("fly" to 14, "fly_pressure" to 13, "outrage" to 9, "outrage_pressure" to 8)) {
            val sample = cases.getValue(id)
            assertEquals(listOf(pp, pp), sample.getAsJsonArray("ppByTurn").map { it.asInt }, id)
            assertTrue(sample.getAsJsonArray("publicLog").any { it.asString.contains("[from]lockedmove") }, id)
        }
        val called = cases.getValue("sleeptalk")
        assertEquals(8, called["callerPp"].asInt)
        assertEquals(35, called["calledPp"].asInt)
        assertTrue(called.getAsJsonArray("publicLog").any {
            it.asString.startsWith("|move|") && it.asString.contains("|Tackle|") &&
                it.asString.contains("[from]move: Sleep Talk")
        })
        for ((id, expectedPp) in mapOf("pressure" to 33, "spite" to 30, "leppa" to 10)) {
            val sample = cases.getValue(id)
            assertEquals(expectedPp, sample["livePp"].asInt, id)
            assertEquals(expectedPp, sample["publishedPp"].asInt, id)
            assertEquals(expectedPp, sample.getAsJsonArray("requestMoves").single().asJsonObject["pp"].asInt, id)
        }
        fun publicLines(id: String) = cases.getValue(id).getAsJsonArray("publicLog").map { it.asString }
        assertTrue(publicLines("pressure").any { it.startsWith("|-ability|") && it.endsWith("|Pressure") })
        assertTrue(publicLines("spite").any { it.startsWith("|-activate|") && it.endsWith("|move: Spite|Tackle|4") })
        assertTrue(publicLines("leppa").any { it.startsWith("|-activate|") && it.endsWith("|item: Leppa Berry|Tackle|[consumed]") })
        val ordinary = cases.getValue("ordinary")
        assertEquals("move", ordinary["requestType"].asString)
        assertEquals(34, ordinary["livePp"].asInt)
        assertEquals(34, ordinary["publishedPp"].asInt)
        assertEquals(34, ordinary.getAsJsonArray("requestMoves").single().asJsonObject["pp"].asInt)
        val pivot = cases.getValue("pivot")
        assertEquals("switch", pivot["requestType"].asString)
        assertEquals(19, pivot["livePp"].asInt)
        assertEquals(20, pivot["publishedPp"].asInt,
            "The native mid-turn request precedes the next PP publication; do not call the cached value exact-current")
        assertTrue(pivot["requestMoves"].isJsonNull)
        val transform = cases.getValue("transform")
        assertEquals("splash", transform["liveMove"].asString)
        assertEquals(5, transform["livePp"].asInt)
        assertEquals(5, transform["publishedPp"].asInt)
        assertEquals("transform", transform["baseMove"].asString)
        assertEquals(9, transform["basePp"].asInt)
        assertEquals("splash", transform.getAsJsonArray("requestMoves").single().asJsonObject["id"].asString)
        assertEquals(5, transform.getAsJsonArray("requestMoves").single().asJsonObject["pp"].asInt)
        println("PP_TIMELINE cases=${cases.size} engine=${result["engineSha256"].asString}")
    }
}
