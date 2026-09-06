package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedTurnOrderDifferentialTest {
    @Test
    fun `native lethal turns agree with public order and cancellation projection`(@TempDir directory: Path) {
        val result = EmbeddedTurnOrderDifferential.compare(directory)
        val ids = result.getAsJsonArray("cases").map { it.asJsonObject["id"].asString }
        assertEquals(8, ids.size)
        assertEquals(setOf("normal-faster", "normal-slower", "priority-over-speed", "trick-room-speed",
            "trick-room-priority", "positive-speed-stage", "negative-speed-stage", "tailwind-over-negative-stage"), ids.toSet())
        assertEquals(0, result["mismatchCount"].asInt, result["mismatches"].toString())
    }
}
