package jbro.cobblemon.mcc.api.client

import java.util.UUID
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccClientContextTest {
    @Test
    fun `listeners hear each change once, with the state before and after`() {
        val state = MccClientContextState()
        val heard = mutableListOf<Pair<MccClientState, MccClientState>>()
        state.listen { previous, current -> heard += previous to current }
        val hub = MccClientState(hubTab = ManagedBattleContentIds.BATTLE_TOWER)
        state.update(hub)
        state.update(hub)
        val battleId = UUID.randomUUID()
        val untagged = MccClientState(battle = MccClientBattle(battleId, spectating = false, tag = null))
        state.update(untagged)
        val tagged = MccClientState(battle = MccClientBattle(battleId, false, MccBattleTag(ManagedBattleContentIds.BATTLE_TOWER, "tier_boss")))
        state.update(tagged)
        state.update(MccClientState.NONE)
        assertEquals(listOf(MccClientState.NONE to hub, hub to untagged, untagged to tagged, tagged to MccClientState.NONE), heard)
        assertTrue(hub.hubOpen)
        assertFalse(tagged.hubOpen)
    }

    @Test
    fun `a closed or failing listener does not stop the others`() {
        val state = MccClientContextState()
        var calls = 0
        state.listen { _, _ -> throw IllegalStateException("broken") }
        val removed = state.listen { _, _ -> calls += 100 }
        state.listen { _, _ -> calls++ }
        removed.close()
        state.update(MccClientState(hubTab = "more_cobblemon_contents:dashboard"))
        assertEquals(1, calls)
        assertEquals("more_cobblemon_contents:dashboard", state.current.hubTab)
    }
}
