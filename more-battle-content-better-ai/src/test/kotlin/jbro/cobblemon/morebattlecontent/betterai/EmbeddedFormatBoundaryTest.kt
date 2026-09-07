package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedFormatBoundaryTest {
    @Test
    fun `unsupported battle format is rejected before creating a native process`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val pair = JsonParser.parseString("""{"battleFormat":"DOUBLE"}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedTeamBattle.NativeSession(directory.resolve("unused-engine"), pair, directory.resolve("output"), 2)
        }
        assertFalse(java.nio.file.Files.exists(directory.resolve("output")))
    }

    @Test
    fun `unsupported formats fail before single slot projection`() {
        val inputs = listOf(
            """{"format":"DOUBLE"}""",
            """{"publicLog":["|gametype|doubles"]}""",
            """{"request":{"active":[{},{}]}}""",
            """{"request":{"forceSwitch":[false,true]}}""",
            """{"request":{"side":{"pokemon":[{"active":true},{"active":true}]}}}""",
            """{"publicLog":["|move|p2b: second|Protect|p2b: second"]}""",
        )
        for (json in inputs) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                EmbeddedTeamInput.context(JsonParser.parseString(json).asJsonObject, UUID(0, 1), 1, 0)
            }
            assertTrue(failure.message.orEmpty().contains("single", ignoreCase = true), json)
        }
    }
}
