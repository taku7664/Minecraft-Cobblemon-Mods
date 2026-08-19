package jbro.cobblemon.morebattlecontent.api.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleContentIdsTest {
    @Test
    fun `tower factory and pvp expose stable distinct content ids`() {
        assertEquals("cobblemon_more_battle_content:battle_tower", ManagedBattleContentIds.BATTLE_TOWER)
        assertEquals("cobblemon_more_battle_content:battle_factory", ManagedBattleContentIds.BATTLE_FACTORY)
        assertEquals("cobblemon_more_battle_content:pvp", ManagedBattleContentIds.PVP)
        assertEquals(3, setOf(
            ManagedBattleContentIds.BATTLE_TOWER,
            ManagedBattleContentIds.BATTLE_FACTORY,
            ManagedBattleContentIds.PVP,
        ).size)
        assertTrue(ManagedBattleContentIds.isValid(ManagedBattleContentIds.PVP))
    }
}
