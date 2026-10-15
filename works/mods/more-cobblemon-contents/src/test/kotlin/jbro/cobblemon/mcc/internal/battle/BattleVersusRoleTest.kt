package jbro.cobblemon.mcc.internal.battle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BattleVersusRoleTest {
    @Test fun `cup encounters retain their tournament title in both difficulties`() {
        assertEquals("tournament" to false, BattleVersusIntros.tierOf("tournament"))
        assertEquals("tournament" to true, BattleVersusIntros.tierOf("hard_tournament"))
        assertEquals("elite_four" to true, BattleVersusIntros.tierOf("hard_elite_four"))
        assertEquals("champion" to false, BattleVersusIntros.tierOf("champion"))
        assertNull(BattleVersusIntros.tierOf("wild_trainer"))
    }
}
