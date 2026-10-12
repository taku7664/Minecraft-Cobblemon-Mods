package jbro.cobblemon.toitem.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoveTransactionTest {
    @Test fun `click exchange puts A in the entry and B on the cursor`() {
        var entry: String? = "B"
        var cursor = "A"
        assertTrue(MoveTransaction.execute({ entry = null; true }, { entry = "A"; cursor = "B"; true }, { entry = "B"; cursor = "A" }))
        assertEquals("A", entry)
        assertEquals("B", cursor)
    }

    @Test fun `exchange failing after insertion restores both Pokemon and cursor item`() {
        var entry: String? = "B"
        var cursor = "A"
        assertThrows(IllegalStateException::class.java) {
            MoveTransaction.execute({ entry = null; true }, { entry = "A"; cursor = "B"; error("sync failed") }, { entry = "B"; cursor = "A" })
        }
        assertEquals("B", entry)
        assertEquals("A", cursor)
    }
    @Test fun `origin removal exception after mutation also rolls back`() {
        var inParty = true
        assertThrows(IllegalStateException::class.java) {
            MoveTransaction.execute({ inParty = false; error("notification failed") }, { fail("origin failed") }, { inParty = true })
        }
        assertTrue(inParty)
    }

    @Test fun `restoration failure leaves the source item and no half inserted Pokemon`() {
        var items = 1
        var inParty = false
        assertThrows(IllegalStateException::class.java) {
            MoveTransaction.execute({ items = 0; true }, { inParty = true; error("placement failed") }, { inParty = false; items = 1 })
        }
        assertEquals(1, items)
        assertFalse(inParty)
    }
    @Test fun `failed inventory insertion or cancelled drop returns the original Pokemon`() {
        var inParty = true
        assertFalse(MoveTransaction.execute({ inParty = false; true }, { false }, { inParty = true }))
        assertTrue(inParty)
    }

    @Test fun `destination exception restores origin`() {
        var inParty = true
        assertThrows(IllegalStateException::class.java) {
            MoveTransaction.execute({ inParty = false; true }, { error("drop failed") }, { inParty = true })
        }
        assertTrue(inParty)
    }

    @Test fun `stale request never delivers another Pokemon`() {
        var deliveries = 0
        assertFalse(MoveTransaction.execute({ false }, { deliveries++; true }, { fail("nothing removed") }))
        assertEquals(0, deliveries)
    }

    @Test fun `successful move has exactly one destination and no rollback`() {
        var inParty = true
        var items = 0
        assertTrue(MoveTransaction.execute({ inParty = false; true }, { items++; true }, { fail("successful move") }))
        assertFalse(inParty)
        assertEquals(1, items)
    }
}
