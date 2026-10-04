package jbro.cobblemon.mcc.api.presentation

import java.util.UUID
import jbro.cobblemon.mcc.api.battle.MccBattleTag
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

    @Test
    fun `the full tag is kept beside the content id`() {
        val state = ManagedBattleContentClientState()
        val battle = UUID.randomUUID()
        val champion = MccBattleTag(ManagedBattleContentIds.LEAGUE_CHALLENGE, "champion", "more_cobblemon_contents_league_challenge:cynthia")
        state.show(battle, champion)
        assertEquals(champion, state.tag(battle))
        assertEquals(ManagedBattleContentIds.LEAGUE_CHALLENGE, state.contentId(battle))
    }
}
