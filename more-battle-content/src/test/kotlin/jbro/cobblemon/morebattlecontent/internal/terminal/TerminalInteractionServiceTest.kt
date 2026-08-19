package jbro.cobblemon.morebattlecontent.internal.terminal

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalInteractionServiceTest {
    private val terminalId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val entryContextId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val service = TerminalInteractionService { entryContextId }

    @Test
    fun `verified nearby interaction receives a server issued context`() {
        val result = service.verify(validSnapshot())

        result as TerminalInteractionResult.Verified
        assertEquals(terminalId, result.terminalId)
        assertEquals(entryContextId, result.entryContextId)
        assertEquals("overworld", result.dimensionId)
        assertEquals(0, result.x)
        assertEquals(0, result.y)
        assertEquals(0, result.z)
    }

    @Test
    fun `different dimension distance missing block entity and permission fail closed`() {
        assertRejected(TerminalInteractionRejection.DIFFERENT_DIMENSION, validSnapshot(playerDimensionId = "the_nether"))
        assertRejected(TerminalInteractionRejection.TOO_FAR, validSnapshot(playerX = 9.0))
        assertRejected(TerminalInteractionRejection.TERMINAL_MISSING, validSnapshot(terminalBlockPresent = false))
        assertRejected(TerminalInteractionRejection.TERMINAL_MISSING, validSnapshot(blockEntityTerminalId = null))
        assertRejected(
            TerminalInteractionRejection.TERMINAL_ID_MISMATCH,
            validSnapshot(blockEntityTerminalId = UUID.fromString("99999999-2222-3333-4444-555555555555")),
        )
        assertRejected(TerminalInteractionRejection.NOT_PERMITTED, validSnapshot(permitted = false))
    }

    @Test
    fun `distance includes the vertical axis and accepts the exact boundary`() {
        assertTrue(service.verify(validSnapshot(playerX = 8.5, playerY = 0.5)) is TerminalInteractionResult.Verified)
        assertRejected(TerminalInteractionRejection.TOO_FAR, validSnapshot(playerY = 8.5001))
        assertRejected(TerminalInteractionRejection.INVALID_POSITION, validSnapshot(playerX = Double.NaN))
    }

    private fun assertRejected(expected: TerminalInteractionRejection, snapshot: TerminalInteractionSnapshot) {
        assertEquals(TerminalInteractionResult.Rejected(expected), service.verify(snapshot))
    }

    private fun validSnapshot(
        playerDimensionId: String = "overworld",
        playerX: Double = 0.5,
        playerY: Double = 0.5,
        blockEntityTerminalId: UUID? = terminalId,
        terminalBlockPresent: Boolean = true,
        permitted: Boolean = true,
    ) = TerminalInteractionSnapshot(
        expectedTerminalId = terminalId,
        blockEntityTerminalId = blockEntityTerminalId,
        terminalDimensionId = "overworld",
        terminalX = 0,
        terminalY = 0,
        terminalZ = 0,
        playerDimensionId = playerDimensionId,
        playerX = playerX,
        playerY = playerY,
        playerZ = 0.5,
        terminalBlockPresent = terminalBlockPresent,
        permitted = permitted,
    )
}
