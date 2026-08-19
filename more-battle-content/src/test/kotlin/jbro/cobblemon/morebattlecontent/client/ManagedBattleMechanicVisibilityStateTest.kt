package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.battles.ShowdownMoveset
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleMechanic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManagedBattleMechanicVisibilityStateTest {
    private val battleId = UUID.randomUUID()
    private val all = ManagedBattleMechanic.entries.toList()

    @Test
    fun `unmanaged battles preserve every gimmick offered by Cobblemon`() {
        val state = ManagedBattleMechanicVisibilityState()

        assertEquals(all, state.visibleMechanics(battleId, all))
    }

    @Test
    fun `tower factory and pvp policies expose only their enabled mechanics`() {
        val state = ManagedBattleMechanicVisibilityState()

        state.show(battleId, setOf(ManagedBattleMechanic.MEGA))
        assertEquals(listOf(ManagedBattleMechanic.MEGA), state.visibleMechanics(battleId, all))

        state.show(battleId, emptySet())
        assertEquals(emptyList<ManagedBattleMechanic>(), state.visibleMechanics(battleId, all))

        state.show(battleId, setOf(ManagedBattleMechanic.DYNAMAX, ManagedBattleMechanic.Z_MOVE))
        assertEquals(
            listOf(ManagedBattleMechanic.DYNAMAX, ManagedBattleMechanic.Z_MOVE),
            state.visibleMechanics(battleId, all),
        )
    }

    @Test
    fun `stale battle end cannot clear a newer managed battle policy`() {
        val state = ManagedBattleMechanicVisibilityState()
        val newerBattleId = UUID.randomUUID()

        state.show(battleId, setOf(ManagedBattleMechanic.MEGA))
        state.show(newerBattleId, setOf(ManagedBattleMechanic.TERA))
        state.hide(battleId)

        assertNull(state.policy(battleId))
        assertEquals(setOf(ManagedBattleMechanic.TERA), state.policy(newerBattleId))
    }

    @Test
    fun `every Cobblemon gimmick is mapped explicitly and ultra burst stays unavailable`() {
        assertEquals(ManagedBattleMechanic.MEGA, managedBattleMechanicFor(ShowdownMoveset.Gimmick.MEGA_EVOLUTION))
        assertEquals(ManagedBattleMechanic.DYNAMAX, managedBattleMechanicFor(ShowdownMoveset.Gimmick.DYNAMAX))
        assertEquals(ManagedBattleMechanic.TERA, managedBattleMechanicFor(ShowdownMoveset.Gimmick.TERASTALLIZATION))
        assertEquals(ManagedBattleMechanic.Z_MOVE, managedBattleMechanicFor(ShowdownMoveset.Gimmick.Z_POWER))
        assertNull(managedBattleMechanicFor(ShowdownMoveset.Gimmick.ULTRA_BURST))
    }
}
