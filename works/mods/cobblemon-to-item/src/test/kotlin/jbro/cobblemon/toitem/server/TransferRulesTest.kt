package jbro.cobblemon.toitem.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TransferRulesTest {
    @Test fun `all entrypoints refuse battles spectators death and missing permission`() {
        assertTrue(TransferRules.actorAllowed(true, false, false, true))
        assertFalse(TransferRules.actorAllowed(true, false, true, true))
        assertFalse(TransferRules.actorAllowed(true, true, false, true))
        assertFalse(TransferRules.actorAllowed(false, false, false, true))
        assertFalse(TransferRules.actorAllowed(true, false, false, false))
    }
    @Test fun `quick restore fills the lowest gap and refuses a full party`() {
        assertEquals(1, TransferRules.restoreSlot(null, listOf(true, false, true, false, false, false)))
        assertEquals(0, TransferRules.restoreSlot(null, List(6) { false }))
        assertNull(TransferRules.restoreSlot(null, List(6) { true }))
    }
    @Test fun `drag restore never overwrites an occupied or invalid party slot`() {
        assertNull(TransferRules.restoreSlot(0, listOf(true, false)))
        assertNull(TransferRules.restoreSlot(-1, List(6) { false }))
        assertNull(TransferRules.restoreSlot(6, List(6) { false }))
        assertEquals(4, TransferRules.restoreSlot(4, List(6) { false }))
    }
    @Test fun `stale identity and stacked copies are rejected`() {
        val id = UUID.randomUUID()
        assertTrue(TransferRules.sourceMatches(1, id, id))
        assertFalse(TransferRules.sourceMatches(1, id, UUID.randomUUID()))
        assertFalse(TransferRules.sourceMatches(1, id, null))
        assertFalse(TransferRules.sourceMatches(2, id, id))
    }
}
