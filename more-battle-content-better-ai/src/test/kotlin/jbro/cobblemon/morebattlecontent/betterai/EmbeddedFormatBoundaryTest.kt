package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedFormatBoundaryTest {
    @Test
    fun `unknown battle format is rejected before creating a native process`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val pair = JsonParser.parseString("""{"battleFormat":"TRIPLE"}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedTeamBattle.NativeSession(directory.resolve("unused-engine"), pair, directory.resolve("output"), 2)
        }
        assertFalse(java.nio.file.Files.exists(directory.resolve("output")))
    }

    @Test
    fun `single and double format boundaries are explicit`() {
        assertEquals(jbro.cobblemon.morebattlecontent.api.ai.BattleFormat.SINGLE,
            EmbeddedTeamInput.battleFormat(JsonParser.parseString(
                """{"format":"SINGLE","publicLog":["|gametype|singles"]}""").asJsonObject))
        assertEquals(jbro.cobblemon.morebattlecontent.api.ai.BattleFormat.DOUBLE,
            EmbeddedTeamInput.battleFormat(JsonParser.parseString(
                """{"format":"DOUBLE","publicLog":["|gametype|doubles"],"request":{"active":[{},{}]}}""").asJsonObject))

        val invalid = listOf(
            """{"format":"TRIPLE"}""",
            """{"format":"SINGLE","publicLog":["|gametype|doubles"]}""",
            """{"format":"SINGLE","request":{"active":[{},{}]}}""",
            """{"format":"DOUBLE","request":{"active":[{},{},{}]}}""",
        )
        for (json in invalid) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                EmbeddedTeamInput.battleFormat(JsonParser.parseString(json).asJsonObject)
            }
            assertTrue(failure.message.orEmpty().isNotBlank(), json)
        }
    }
}
