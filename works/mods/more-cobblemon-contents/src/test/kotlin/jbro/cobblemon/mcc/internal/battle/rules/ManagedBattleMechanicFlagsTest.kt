package jbro.cobblemon.mcc.internal.battle.rules

import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedBattleMechanicFlagsTest {
    @Test
    fun `every valid mask reaches the rules without unsupported or extra mechanics`() {
        for (flags in 0..BattleMechanicFlags.ALL) {
            val submitted = submittedMechanics(flags)
            assertFalse(ManagedSubmittedMechanic.UNSUPPORTED in submitted)
            assertEquals(Integer.bitCount(flags), submitted.size)
            assertEquals(flags, submitted.fold(0) { mask, mechanic -> mask or mechanic.flag })
        }
        assertThrows(IllegalArgumentException::class.java) { submittedMechanics(16) }
    }
}
