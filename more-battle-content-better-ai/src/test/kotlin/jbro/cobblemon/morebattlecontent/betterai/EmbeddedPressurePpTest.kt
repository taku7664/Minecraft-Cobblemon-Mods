package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedPressurePpTest {
    @Test
    fun `native doubles Pressure counts effective opposing targets not all active abilities`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.pressurePp(directory)
        val cases = result.getAsJsonArray("cases").associate { it.asJsonObject["id"].asString to it.asJsonObject }
        val expected = mapOf("selected" to 2, "spread" to 3, "self" to 1, "ally_pressure" to 3, "suppressed" to 1)
        assertEquals(expected.keys, cases.keys)
        expected.forEach { (id, spent) ->
            assertEquals(spent, cases.getValue(id)["spent"].asInt, id)
        }
        println("PRESSURE_PP cases=${cases.size} engine=${result["engineSha256"].asString}")
    }
}
