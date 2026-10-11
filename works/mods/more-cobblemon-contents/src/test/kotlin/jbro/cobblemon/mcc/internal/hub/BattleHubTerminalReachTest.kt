package jbro.cobblemon.mcc.internal.hub

import java.util.UUID
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleHubTerminalReachTest {
    private val terminal = TerminalInteractionResult.Verified(UUID(0, 1), UUID(0, 2), "minecraft:overworld", 10, 64, 10)

    @Test
    fun `a hub opened at a terminal reaches its tabs only while the player stays near it`() {
        assertTrue(terminalStillInReach("minecraft:overworld", 10.5, 64.0, 10.5, terminal))
        assertTrue(terminalStillInReach("minecraft:overworld", 10.5 + HUB_TERMINAL_REACH - 1, 64.5, 10.5, terminal))
        assertFalse(terminalStillInReach("minecraft:overworld", 10.5 + HUB_TERMINAL_REACH + 1, 64.5, 10.5, terminal))
        assertFalse(terminalStillInReach("minecraft:the_nether", 10.5, 64.0, 10.5, terminal))
        assertFalse(terminalStillInReach("minecraft:overworld", Double.NaN, 64.0, 10.5, terminal))
    }
}
