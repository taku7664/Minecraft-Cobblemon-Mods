package jbro.cobblemon.mcc.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PvpBattleOutcomeTest {
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)

    @Test
    fun `a played out battle keeps Cobblemon's winner whatever the forfeit ledger says`() {
        assertEquals(PvpBattleOutcome(first, second), resolve(firstWon = true, forfeits = setOf(first)))
        assertEquals(PvpBattleOutcome(second, first), resolve(secondWon = true))
    }

    @Test
    fun `a forced tie after one player left counts as that player's loss`() {
        assertEquals(PvpBattleOutcome(second, first), resolve(forfeits = setOf(first)))
        assertEquals(PvpBattleOutcome(first, second), resolve(forfeits = setOf(second, UUID(0, 99))))
    }

    @Test
    fun `a forced tie with both or neither player leaving stays a no contest`() {
        assertNull(resolve())
        assertNull(resolve(forfeits = setOf(first, second)))
        assertNull(resolve(forfeits = setOf(UUID(0, 99))))
    }

    private fun resolve(firstWon: Boolean = false, secondWon: Boolean = false, forfeits: Set<UUID> = emptySet()) =
        resolvePvpBattleOutcome(first, second, firstWon, secondWon, forfeits)
}
