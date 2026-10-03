package jbro.cobblemon.mcc.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Cobblemon173RequestSlotRulesTest {
    @Test fun `a living unforced slot passes in either double position`() {
        for (slot in 0..1) {
            val forced = listOf(slot == 0, slot == 1)
            assertTrue(Cobblemon173RequestSlotRules.mustPass(true, forced[1 - slot], true, false))
            assertFalse(Cobblemon173RequestSlotRules.mustPass(true, forced[slot], false, false))
        }
    }
    @Test fun `fainted and Commander actors cannot choose moves`() {
        assertTrue(Cobblemon173RequestSlotRules.mustPass(false, false, false, false))
        assertTrue(Cobblemon173RequestSlotRules.mustPass(false, false, true, true))
        assertFalse(Cobblemon173RequestSlotRules.mustPass(false, false, true, false))
    }
    @Test fun `revival accepts only fainted targets including an unfilled active slot`() {
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(true, true, false))
        assertTrue(Cobblemon173RequestSlotRules.eligibleReplacement(true, false, false))
        assertTrue(Cobblemon173RequestSlotRules.eligibleReplacement(true, false, true))
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(false, false, false))
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(false, true, true))
    }
    @Test fun `two forced slots and one bench require exactly one switch`() {
        assertEquals(1, Cobblemon173RequestSlotRules.replacementCount(2, 1))
        assertEquals(2, Cobblemon173RequestSlotRules.replacementCount(2, 3))
    }
}
