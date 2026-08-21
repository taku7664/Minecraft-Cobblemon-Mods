package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpBattleCamCycleRecoveryTest {
    @Test
    fun `recovers only a new cycle key press that remained off`() {
        assertTrue(PvpBattleCamCycleRecovery.shouldRecover(true, false, "OFF", "OFF"))
        assertFalse(PvpBattleCamCycleRecovery.shouldRecover(true, false, "OFF", "AUTO"))
        assertFalse(PvpBattleCamCycleRecovery.shouldRecover(true, false, "MANUAL", "OFF"))
        assertFalse(PvpBattleCamCycleRecovery.shouldRecover(true, true, "OFF", "OFF"))
        assertFalse(PvpBattleCamCycleRecovery.shouldRecover(false, false, "OFF", "OFF"))
    }
}
