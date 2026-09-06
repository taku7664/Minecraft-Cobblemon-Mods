package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedDoublesRetargetTest {
    @Test
    fun `attack on an already fainted foe reaches the remaining foe`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.doublesRetarget(directory)
        val cases = result.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(listOf("focused", "split"), cases.map { it["id"].asString })
        for (case in cases) {
            val events = case.getAsJsonArray("events").map { it.asString }
            val faint = events.indexOfFirst { it.startsWith("|faint|p2a:") }
            val second = events.indexOfFirst { it.startsWith("|move|p1b:") && it.contains("|Seismic Toss|p2b:") }
            assertTrue(faint >= 0 && second > faint, events.joinToString("\n"))
            assertTrue(events.drop(second + 1).any { it.startsWith("|-damage|p2b:") })
            assertEquals(50, case["remainingMaxHp"].asInt - case["remainingHp"].asInt)
        }
        assertEquals(cases[1]["remainingHp"], cases[0]["remainingHp"])
        assertEquals(64, result["engineSha256"].asString.length)
        println("DOUBLES_RETARGET cases=${cases.size} fixedDamage=50 engine=${result["engineSha256"].asString}")
    }
}
