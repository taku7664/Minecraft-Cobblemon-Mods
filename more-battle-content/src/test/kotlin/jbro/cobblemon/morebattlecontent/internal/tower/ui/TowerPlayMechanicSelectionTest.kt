package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchRequest
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPlayMechanicSelectionTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val contextId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `tower exposes exactly the three approved session mechanics`() {
        assertEquals(
            listOf(MajorBattleMechanic.MEGA, MajorBattleMechanic.DYNAMAX, MajorBattleMechanic.TERA),
            MajorBattleMechanic.entries,
        )
    }

    @Test
    fun `new challenge starts without an implicit mechanic and cannot lock team`() {
        val service = service()
        var state = service.open(playerId, request())
        party().take(3).forEachIndexed { index, slot ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(0, index.toLong() + 1), contextId, state.revision, slot.pokemonId),
            ))
        }

        val result = service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(0, 10), contextId, state.revision),
        ) as TowerPlayMutationResult.Rejected

        assertEquals(null, state.selectedMechanic)
        assertFalse(state.mechanicLocked)
        assertEquals(TowerPlayMessageKeys.MECHANIC_REQUIRED, result.messageKey)
        assertEquals(TowerPlayPhase.SELECTING, service.current(playerId)?.phase)
    }

    @Test
    fun `mechanic selected before floor one reaches launcher and locks for the run`() {
        val launches = ArrayList<TowerBattleLaunchRequest>()
        val service = service(TowerBattleLauncher { request ->
            launches += request
            TowerBattleLaunchResult.Started(UUID(0, 99))
        })
        var state = service.open(playerId, request())
        state = accepted(service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(0, 1), contextId, state.revision, MajorBattleMechanic.TERA),
        ))
        party().take(3).forEachIndexed { index, slot ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(0, index.toLong() + 2), contextId, state.revision, slot.pokemonId),
            ))
        }
        state = accepted(service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(0, 10), contextId, state.revision),
        ))

        val active = accepted(service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 11), contextId, state.revision),
        ))
        val change = service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(0, 12), contextId, active.revision, MajorBattleMechanic.MEGA),
        ) as TowerPlayMutationResult.Rejected

        assertEquals(MajorBattleMechanic.TERA, launches.single().mechanic)
        assertEquals(MajorBattleMechanic.TERA, active.selectedMechanic)
        assertTrue(active.mechanicLocked)
        assertEquals(TowerPlayMessageKeys.PHASE_INVALID, change.messageKey)
    }

    private fun service(
        launcher: TowerBattleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Unavailable },
    ) = TowerPlaySessionService(launcher, TestTowerRegisteredTeamSnapshots) { contextId }

    private fun request() = TowerPlayOpenRequest(
        party(),
        TowerBattleFormat.SINGLE,
        TowerBattleFormat.entries.associateWith(TowerProgress::initial),
        0,
    )

    private fun party(): List<TowerPlayPartySlot> = (1..6).map { index ->
        TowerPlayPartySlot(
            index - 1,
            UUID(0, index.toLong()),
            "cobblemon:species_$index",
            if (index == 6) null else "minecraft:item_$index",
            40 + index,
            minOf(40 + index, 50),
        )
    }

    private fun accepted(result: TowerPlayMutationResult): TowerPlayViewState =
        (result as TowerPlayMutationResult.Accepted).state
}
