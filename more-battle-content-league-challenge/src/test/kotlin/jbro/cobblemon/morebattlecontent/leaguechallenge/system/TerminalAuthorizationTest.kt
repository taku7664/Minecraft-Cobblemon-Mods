package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalAuthorizationTest {
    private val id = UUID.randomUUID()
    private val expected = TerminalAnchor(id, "minecraft:overworld", 0, 64, 0)
    private val valid = TerminalObservation(id, "minecraft:overworld", .5, 65.0, .5, true, true, true, 10)

    @Test fun `nearby living player at same terminal is authorized`() {
        assertNull(TerminalAuthorization.reject(expected, valid, 0))
    }
    @Test fun `distance dimension replacement and permission fail closed`() {
        listOf(valid.copy(playerX = 20.0), valid.copy(dimension = "minecraft:the_nether"),
            valid.copy(terminalId = UUID.randomUUID()), valid.copy(blockPresent = false),
            valid.copy(permitted = false), valid.copy(livingNonSpectator = false), valid.copy(playerX = Double.NaN))
            .forEach { assertEquals("terminal_invalid", TerminalAuthorization.reject(expected, it, 0)) }
    }
    @Test fun `expired session must be reopened`() {
        assertEquals("terminal_expired", TerminalAuthorization.reject(expected, valid.copy(tick = 12000), 0))
    }
}
