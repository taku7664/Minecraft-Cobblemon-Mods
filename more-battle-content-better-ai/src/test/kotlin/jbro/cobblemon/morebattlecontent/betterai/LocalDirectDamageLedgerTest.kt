package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageLedger
import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageRecipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalDirectDamageLedgerTest {
    private val actor = UUID(0, 1)
    private val target = UUID(0, 2)

    @Test
    fun `sequential hits add while distinct attackers and targets stay separate`() {
        val other = UUID(0, 3)
        val ledger = LocalDirectDamageLedger.hit(actor, target, 0.1) +
            LocalDirectDamageLedger.hit(actor, target, 0.2) +
            LocalDirectDamageLedger.hit(other, target, 0.4) +
            LocalDirectDamageLedger.hit(actor, other, 0.5)
        assertEquals(3, ledger.amounts.size)
        assertEquals(0.3, ledger.amounts.getValue(LocalDirectDamageRecipient(actor, target)), 1e-9)
        assertEquals(0.4, ledger.amounts.getValue(LocalDirectDamageRecipient(other, target)), 1e-9)
        assertEquals(0.5, ledger.amounts.getValue(LocalDirectDamageRecipient(actor, other)), 1e-9)
    }

    @Test
    fun `merging uses probability mass including absent hits and survives a second merge`() {
        val hit = LocalDirectDamageLedger.hit(actor, target, 0.4)
        val merged = LocalDirectDamageLedger.weighted(listOf(0.25 to hit, 0.75 to LocalDirectDamageLedger.EMPTY))
        assertEquals(0.1, merged.amounts.getValue(LocalDirectDamageRecipient(actor, target)), 1e-9)
        val mergedAgain = LocalDirectDamageLedger.weighted(listOf(0.6 to merged, 0.4 to hit))
        assertEquals(0.22, mergedAgain.amounts.getValue(LocalDirectDamageRecipient(actor, target)), 1e-9)
    }
}
