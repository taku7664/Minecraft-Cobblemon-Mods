package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedDamageDifferentialTest {
    @Test
    fun `all exact base damage rolls and knockout thresholds match the embedded engine`(@TempDir directory: Path) {
        val result = EmbeddedDamageDifferential.compare(directory)
        assertEquals(144, result["cases"].asInt)
        assertTrue(result["koThresholdChecks"].asInt >= 20_000)
        assertEquals(0, result["mismatchCount"].asInt, result["mismatches"].toString())
    }
}
