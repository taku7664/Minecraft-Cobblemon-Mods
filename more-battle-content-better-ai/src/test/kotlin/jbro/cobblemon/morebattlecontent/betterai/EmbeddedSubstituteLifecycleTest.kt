package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedSubstituteLifecycleTest {
    @Test
    fun `native substitute lifetime includes transfer and same turn recreation`(@TempDir directory: Path) {
        val output = EmbeddedShowdownOracle.substituteLifecycle(directory)
        assertEquals("COMPLETE", output["status"].asString)
        val cases = output.getAsJsonArray("cases").associate { it.asJsonObject["id"].asString to it.asJsonObject }
        assertEquals(setOf("repeat", "break-recreate", "ordinary-switch", "batonpass", "shedtail"), cases.keys)
        fun log(id: String) = cases.getValue(id).getAsJsonArray("events").map { it.asString }
        fun active(id: String) = cases.getValue(id)["activeSubstitute"].asBoolean
        assertTrue(log("repeat").any { it.startsWith("|-fail|") && it.contains("move: Substitute") })
        assertTrue(active("repeat"))
        val recreated = log("break-recreate")
        val ended = recreated.indexOfFirst { it.startsWith("|-end|") && it.endsWith("|Substitute") }
        val started = recreated.indexOfFirst { it.startsWith("|-start|") && it.endsWith("|Substitute") }
        assertTrue(ended >= 0 && started > ended)
        assertTrue(recreated.none { it.startsWith("|-fail|") })
        assertTrue(active("break-recreate"))
        assertFalse(active("ordinary-switch"))
        for (id in listOf("batonpass", "shedtail")) {
            assertTrue(active(id))
            assertTrue(log(id).any { it.startsWith("|switch|") && it.contains("[from]") })
            assertTrue(log(id).none { it.startsWith("|-start|") && it.contains("Substitute") })
        }
        assertTrue(cases.values.all { record -> record.getAsJsonArray("events").none { it.asString.contains("pp_update") } })
    }
}
