package jbro.cobblemon.morebattlecontent.api.presentation

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManagedBattleContentClientStateTest {
    @Test
    fun `late spectator state is addressable by battle and removed independently`() {
        val state = ManagedBattleContentClientState()
        val towerBattle = UUID.randomUUID()
        val pvpBattle = UUID.randomUUID()

        state.show(towerBattle, ManagedBattleContentIds.BATTLE_TOWER)
        state.show(pvpBattle, ManagedBattleContentIds.PVP)

        assertEquals(ManagedBattleContentIds.BATTLE_TOWER, state.contentId(towerBattle))
        assertEquals(ManagedBattleContentIds.PVP, state.contentId(pvpBattle))
        state.hide(towerBattle)
        assertNull(state.contentId(towerBattle))
        assertEquals(ManagedBattleContentIds.PVP, state.contentId(pvpBattle))
        state.clear()
        assertNull(state.contentId(pvpBattle))
    }
}
