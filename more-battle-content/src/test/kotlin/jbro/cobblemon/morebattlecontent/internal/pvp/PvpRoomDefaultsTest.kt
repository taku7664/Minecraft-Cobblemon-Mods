package jbro.cobblemon.morebattlecontent.internal.pvp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpRoomDefaultsTest {
    @Test
    fun `new rooms enable only mega by default`() {
        assertEquals(setOf(PvpBattleMechanic.MEGA), PvpRoomDefaults.ENABLED_MECHANICS)
    }
}
