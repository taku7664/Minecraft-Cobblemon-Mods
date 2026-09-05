package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedShowdownOracleTest {
    @Test
    fun `embedded engine independently executes switching damage fainting and battle end`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.run(directory, hiddenVariant = false)
        assertEquals("COMPLETE", result["status"].asString)
        assertEquals("p1", result["winner"].asString)
        val log = result.getAsJsonArray("publicLog").map { it.asString }
        assertTrue(log.any { it.startsWith("|switch|p1a:") && it.contains("|Raichu,") })
        assertTrue(log.any { it.startsWith("|-damage|p2a:") })
        assertEquals(2, log.count { it.startsWith("|faint|p2a:") })
        assertTrue(log.any { it == "|win|p1" })
        assertEquals(64, result["engineSha256"].asString.length)
        assertFalse(result["addonRegistrationsLoaded"].asBoolean)
    }

    @Test
    fun `hidden unused opponent set changes do not enter public observation`(@TempDir directory: Path) {
        val original = EmbeddedShowdownOracle.run(directory.resolve("original"), false)
        val changed = EmbeddedShowdownOracle.run(directory.resolve("changed"), true)
        assertEquals(original["initialPublicObservation"], changed["initialPublicObservation"])
        assertEquals(original["publicLog"], changed["publicLog"])
        assertEquals(original["refereeFinalState"], changed["refereeFinalState"])
        assertNotEquals(original["refereeInitialOpponent"], changed["refereeInitialOpponent"])
        assertFalse(original["initialPublicObservation"].toString().contains("splash"))
        assertFalse(changed["initialPublicObservation"].toString().contains("tackle"))
        assertTrue(changed["refereeRawLog"].toString().contains("pp_update"))
        assertFalse(changed["publicLog"].toString().contains("pp_update"))
    }

    @Test
    fun `extraction refuses archive paths outside its directory`(@TempDir directory: Path) {
        assertThrows(IllegalArgumentException::class.java) { EmbeddedShowdownOracle.entryPath(directory, "../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedShowdownOracle.entryPath(directory, "C:/escape.js") }
        assertEquals(directory.resolve("sim/battle.js"), EmbeddedShowdownOracle.entryPath(directory, "sim/battle.js"))
    }
}
