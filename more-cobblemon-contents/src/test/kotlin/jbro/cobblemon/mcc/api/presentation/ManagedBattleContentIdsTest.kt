package jbro.cobblemon.mcc.api.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleContentIdsTest {
    @Test
    fun `tower factory and pvp expose stable distinct content ids`() {
        assertEquals("more_cobblemon_contents:battle_tower", ManagedBattleContentIds.BATTLE_TOWER)
        assertEquals("more_cobblemon_contents:battle_factory", ManagedBattleContentIds.BATTLE_FACTORY)
        assertEquals("more_cobblemon_contents:pvp", ManagedBattleContentIds.PVP)
        assertEquals(3, setOf(
            ManagedBattleContentIds.BATTLE_TOWER,
            ManagedBattleContentIds.BATTLE_FACTORY,
            ManagedBattleContentIds.PVP,
        ).size)
        assertTrue(ManagedBattleContentIds.isValid(ManagedBattleContentIds.PVP))
    }
}
