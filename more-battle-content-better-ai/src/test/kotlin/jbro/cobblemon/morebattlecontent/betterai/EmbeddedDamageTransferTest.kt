package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Path
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedDamageTransferTest {
    @Test
    fun `native drain and recoil transfer damage HP rather than target HP fractions`(@TempDir directory: Path) {
        val result = EmbeddedShowdownOracle.damageTransfer(directory)
        val cases = result.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(8, cases.size)
        cases.forEach { sample ->
            val id = sample["id"].asString
            val dealt = sample["targetBefore"].asInt - sample["targetAfter"].asInt
            val actorMax = sample["actorMax"].asInt
            val targetMax = sample["targetMax"].asInt
            assertNotEquals(actorMax, targetMax, id)
            assertTrue(dealt > 0, id)
            val expectedHp = (dealt.toDouble() * sample["numerator"].asInt / sample["denominator"].asInt).roundToInt().coerceAtLeast(1)
            val before = sample["actorBefore"].asInt
            val after = sample["actorAfter"].asInt
            val drain = sample["kind"].asString == "drain"
            assertEquals(if (drain) (before + expectedHp).coerceAtMost(actorMax) else (before - expectedHp).coerceAtLeast(0), after, id)
            assertEquals(if (drain) "-heal" else "-damage", sample["selfEvent"].asString, id)
        }
        println("DAMAGE_TRANSFER cases=${cases.size} engine=${result["engineSha256"].asString}")
    }
}
