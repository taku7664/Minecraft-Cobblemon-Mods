package jbro.cobblemon.morebattlecontent.internal.pvp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpArenaProtectionPolicyTest {
    @Test
    fun `ordinary players cannot edit the lounge dimension`() {
        assertTrue(PvpArenaProtectionPolicy.protects(inLoungeDimension = true, hasBypassPermission = false))
    }

    @Test
    fun `operators keep a bypass so a damaged arena stays fixable`() {
        assertFalse(PvpArenaProtectionPolicy.protects(inLoungeDimension = true, hasBypassPermission = true))
        assertEquals(2, PvpArenaProtectionPolicy.BYPASS_PERMISSION_LEVEL)
    }

    @Test
    fun `every other dimension keeps normal building rules`() {
        assertFalse(PvpArenaProtectionPolicy.protects(inLoungeDimension = false, hasBypassPermission = false))
        assertFalse(PvpArenaProtectionPolicy.protects(inLoungeDimension = false, hasBypassPermission = true))
    }
}
