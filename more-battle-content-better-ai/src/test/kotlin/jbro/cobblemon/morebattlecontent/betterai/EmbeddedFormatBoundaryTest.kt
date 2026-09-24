package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
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

    @Test
    fun `single active slot is unambiguous but doubles require the bridge mapping`() {
        val none = JsonParser.parseString("{}").asJsonObject
        val mapped = JsonParser.parseString(
            """{"p1:00000000-0000-0000-0000-000000000101":1}""",
        ).asJsonObject
        val ident = "p1: 00000000-0000-0000-0000-000000000101"

        assertEquals(0, EmbeddedTeamInput.ownActiveSlot(BattleFormat.SINGLE, none, ident, active = true))
        assertNull(EmbeddedTeamInput.ownActiveSlot(BattleFormat.DOUBLE, none, ident, active = false))
        assertEquals(1, EmbeddedTeamInput.ownActiveSlot(BattleFormat.DOUBLE, mapped, ident, active = true))
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedTeamInput.ownActiveSlot(BattleFormat.DOUBLE, none, ident, active = true)
        }
    }
}
