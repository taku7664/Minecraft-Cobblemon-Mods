package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchRequest
import jbro.cobblemon.mcc.internal.tower.TowerBattleLauncher
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.mcc.internal.tower.TowerBattleOutcome
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerProgressUpdate
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPlayModeTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val contextId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val battleId = UUID(0, 99)
    private var requestIds = 0L

    @Test
    fun `a new challenger opens in Normal and cannot choose Endless yet`() {
        val service = service()
        val state = service.open(playerId, request(normalBest = 0))

        assertEquals(TowerMode.NORMAL, state.mode)
        assertFalse(state.endlessUnlocked)
        val change = service.mutate(playerId, TowerPlayIntent.ChangeMode(next(), contextId, state.revision, TowerMode.ENDLESS))
            as TowerPlayMutationResult.Rejected
        assertEquals(TowerPlayMessageKeys.ENDLESS_LOCKED, change.messageKey)
    }

    @Test
    fun `a challenger who cleared Normal opens in Endless and can switch between the modes' own streaks`() {
        val service = service()
        val state = service.open(playerId, request(normalBest = 20, endlessCurrent = 7))

        assertEquals(TowerMode.ENDLESS, state.mode)
        assertEquals(7, state.currentWinStreak)
        val normal = accepted(service.mutate(playerId, TowerPlayIntent.ChangeMode(next(), contextId, state.revision, TowerMode.NORMAL)))
        assertEquals(TowerMode.NORMAL, normal.mode)
        assertEquals(0, normal.currentWinStreak)
        assertEquals(20, normal.bestWinStreak)
        assertTrue(normal.endlessUnlocked)
    }

    @Test
    fun `the 20th Normal win clears the run, starts the next from the first battle and opens Endless`() {
        val launches = ArrayList<TowerBattleLaunchRequest>()
        val recorded = ArrayList<TowerProgressUpdate>()
        val service = service(TowerBattleLauncher { request ->
            launches += request
            TowerBattleLaunchResult.Started(battleId)
        })
        var state = service.open(playerId, request(normalBest = 19, normalCurrent = 19))
        assertEquals(TowerMode.NORMAL, state.mode)
        state = start(service, state)

        val completion = service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN) { _, update -> recorded += update }
            as TowerPlayBattleCompletionResult.Completed

        assertEquals(TowerMode.NORMAL, launches.single().progress.mode)
        assertTrue(recorded.single().cleared)
        val cleared = completion.state
        assertEquals(TowerPlayPhase.SELECTING, cleared.phase)
        assertEquals(0, cleared.currentWinStreak)
        assertEquals(20, cleared.bestWinStreak)
        assertTrue(cleared.endlessUnlocked)
        assertFalse(cleared.mechanicLocked)
        assertEquals(0, service.progress(playerId)?.getValue(TowerTrack(TowerBattleFormat.SINGLE, TowerMode.NORMAL))?.currentWinStreak)
        val endless = accepted(service.mutate(playerId, TowerPlayIntent.ChangeMode(next(), contextId, cleared.revision, TowerMode.ENDLESS)))
        assertEquals(TowerMode.ENDLESS, endless.mode)
    }

    private fun start(service: TowerPlaySessionService, opened: TowerPlayViewState): TowerPlayViewState {
        var state = opened
        party().take(3).forEach { slot ->
            state = accepted(service.mutate(playerId, TowerPlayIntent.ToggleSelection(next(), contextId, state.revision, slot.pokemonId)))
        }
        state = accepted(service.mutate(playerId, TowerPlayIntent.LockTeam(next(), contextId, state.revision)))
        return accepted(service.mutate(playerId, TowerPlayIntent.Start(next(), contextId, state.revision)))
    }

    private fun next() = UUID(0, ++requestIds)

    private fun service(
        launcher: TowerBattleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Unavailable },
    ) = TowerPlaySessionService(launcher, TestTowerRegisteredTeamSnapshots) { contextId }

    private fun request(normalBest: Int, normalCurrent: Int = 0, endlessCurrent: Int = 0) = TowerPlayOpenRequest(
        party(),
        TowerBattleFormat.SINGLE,
        TowerTrack.entries.associateWith { track ->
            when (track.mode) {
                TowerMode.NORMAL -> TowerProgress(track.format, normalCurrent, normalBest, TowerMode.NORMAL)
                TowerMode.ENDLESS -> TowerProgress(track.format, endlessCurrent, endlessCurrent)
            }
        },
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
