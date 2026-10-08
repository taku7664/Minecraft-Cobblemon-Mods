package jbro.cobblemon.mcc.api.rules

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BattleMechanicFlagsTest {
    @Test
    fun `PvE request accepts combinations and rejects unknown flags before starting a battle`() {
        fun request(flags: Int) = jbro.cobblemon.mcc.api.battle.ManagedPveBattles.Request(
            transactionId = java.util.UUID.randomUUID(), contentId = "test:battle", trainerId = "test:trainer",
            trainerNameKey = "test.trainer", lockedParty = emptyList(), opponentProperties = emptyList(),
            mechanicFlags = flags,
        )
        val flags = BattleMechanicFlags.MEGA or BattleMechanicFlags.DYNAMAX
        assertEquals(flags, request(flags).mechanicFlags)
        assertThrows(IllegalArgumentException::class.java) { request(16) }
    }

    @Test
    fun `flags have stable distinct bits and combine without losing mechanics`() {
        assertEquals(0, BattleMechanicFlags.NONE)
        assertEquals(1, BattleMechanicFlags.MEGA)
        assertEquals(2, BattleMechanicFlags.DYNAMAX)
        assertEquals(4, BattleMechanicFlags.TERA)
        assertEquals(8, BattleMechanicFlags.Z_MOVE)
        val flags = BattleMechanicFlags.MEGA or BattleMechanicFlags.DYNAMAX or BattleMechanicFlags.Z_MOVE
        assertTrue(BattleMechanicFlags.contains(flags, BattleMechanicFlags.MEGA))
        assertTrue(BattleMechanicFlags.contains(flags, BattleMechanicFlags.DYNAMAX))
        assertFalse(BattleMechanicFlags.contains(flags, BattleMechanicFlags.TERA))
        assertEquals(BattleMechanicFlags.NONE, BattleMechanicFlags.fromMajor(null))
        MajorBattleMechanic.entries.forEach { mechanic ->
            assertEquals(1, Integer.bitCount(BattleMechanicFlags.fromMajor(mechanic)))
        }
    }

    @Test
    fun `all valid masks are accepted but unknown bits and negative masks are rejected`() {
        (0..15).forEach { BattleMechanicFlags.requireValid(it) }
        listOf(16, 17, -1, Int.MIN_VALUE).forEach { mask ->
            assertThrows(IllegalArgumentException::class.java) { BattleMechanicFlags.requireValid(mask) }
        }
    }
}
