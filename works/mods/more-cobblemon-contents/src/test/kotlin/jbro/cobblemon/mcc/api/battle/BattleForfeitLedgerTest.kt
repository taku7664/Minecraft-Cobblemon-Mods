package jbro.cobblemon.mcc.api.battle

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleForfeitLedgerTest {
    private val battle = UUID(1, 1)
    private val other = UUID(1, 2)
    private val alice = UUID(0, 1)
    private val bob = UUID(0, 2)

    @Test
    fun `players who die or log out are remembered by battle`() {
        val ledger = BattleForfeitLedger()

        ledger.mark(battle, alice)
        ledger.mark(battle, bob)
        ledger.mark(other, bob)

        assertEquals(setOf(alice, bob), ledger.of(battle))
        assertEquals(setOf(bob), ledger.of(other))
        assertTrue(ledger.of(UUID(1, 3)).isEmpty())
    }

    @Test
    fun `old forfeits are forgotten so finished battles do not pile up`() {
        var now = 0L
        val ledger = BattleForfeitLedger(retentionNanos = 100, clock = { now })
        ledger.mark(battle, alice)

        now = 101
        assertTrue(ledger.of(battle).isEmpty())
        ledger.mark(other, bob)
        assertTrue(ledger.of(battle).isEmpty())
        assertEquals(setOf(bob), ledger.of(other))
    }
}
