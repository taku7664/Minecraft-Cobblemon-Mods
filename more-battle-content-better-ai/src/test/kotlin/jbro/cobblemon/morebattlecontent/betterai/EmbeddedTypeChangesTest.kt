package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedTypeChangesTest {
    @Test
    fun `native public type protocol matches replacement addition and switch lifetime`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.typeChanges(directory)
        assertEquals("COMPLETE", result["status"].asString)
        val cases = result.getAsJsonArray("cases").associate { it.asJsonObject["id"].asString to it.asJsonObject }
        assertEquals(3, cases.size)
        fun checkpoints(id: String) = cases.getValue(id).getAsJsonArray("checkpoints").map { it.asJsonObject }
        fun types(id: String) = checkpoints(id).map { point ->
            point.getAsJsonObject("referee").getAsJsonArray("p2Types").map { it.asString }.toSet()
        }
        assertEquals(listOf(setOf("electric"), setOf("water"), setOf("normal"), setOf("electric")),
            types("soak-switch-reset"))
        assertEquals(listOf(setOf("electric"), setOf("electric", "grass"), setOf("electric", "ghost"), setOf("water")),
            types("added-type-replacement"))
        val reflected = checkpoints("reflect-type-unexplicit").last()
        val changes = reflected.getAsJsonArray("publicLog").map { it.asString }.filter { it.contains("|typechange|") }
        assertEquals("[from] move: Reflect Type", changes.first().split('|')[4])
        assertEquals("Electric", changes.last().split('|')[4])
        assertTrue(changes.last().endsWith("|[silent]"))
        assertEquals(listOf("electric"), reflected.getAsJsonObject("referee").getAsJsonArray("p1Types").map { it.asString })
        assertTrue(cases.values.flatMap { it.getAsJsonArray("checkpoints").toList() }.all { point ->
            point.asJsonObject.getAsJsonArray("publicLog").none { it.asString.contains("pp_update") }
        })
    }
}
