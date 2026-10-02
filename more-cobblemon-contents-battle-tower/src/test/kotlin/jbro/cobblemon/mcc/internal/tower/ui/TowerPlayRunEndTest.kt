package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.mcc.internal.tower.TowerBattleLauncher
import jbro.cobblemon.mcc.internal.tower.TowerBattleOutcome
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeam
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshots
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.internal.tower.clearedNormalWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A run ends at a loss, a Normal clear or a retirement: the team and the rules are picked again for the next. */
class TowerPlayRunEndTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val contextId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val battleId = UUID.fromString("22222222-3333-4444-5555-666666666666")
    private val track = TowerTrack(TowerBattleFormat.SINGLE, TowerMode.ENDLESS)
    private var requestIds = 0L
    private val snapshots = ArrayList<List<UUID>>()
    private val retirements = ArrayList<TowerTrack>()

    @Test
    fun `a loss opens the rules and lets the next run register a new team`() {
        val service = service()
        val active = start(service, service.open(playerId, request(currentWinStreak = 7)))
        assertTrue(active.runStarted)

        val lost = (service.completeBattle(playerId, battleId, TowerBattleOutcome.LOSS)
            as TowerPlayBattleCompletionResult.Completed).state

        assertEquals(TowerPlayPhase.SELECTING, lost.phase)
        assertEquals(0, lost.currentWinStreak)
        assertEquals(7, lost.bestWinStreak)
        assertFalse(lost.runStarted)
        assertTrue(lost.selectedPokemonOrder.isEmpty())
        // The rules change again, and the next lock registers the party the challenger has now.
        var state = accepted(service.mutate(playerId,
            TowerPlayIntent.ChangeMechanic(next(), contextId, lost.revision, MajorBattleMechanic.TERA)))
        state = accepted(service.mutate(playerId,
            TowerPlayIntent.ChangeLegendaryClassAllowed(next(), contextId, state.revision, false)))
        val changed = party(offset = 10)
        state = service.refreshParty(playerId, changed)!!
        assertEquals(changed, state.party)
        lock(service, state)
        assertEquals(listOf(party().map { it.pokemonId }, changed.map { it.pokemonId }), snapshots)
    }

    @Test
    fun `a win keeps the run, its registered team and its rules`() {
        val service = service()
        start(service, service.open(playerId, request(currentWinStreak = 7)))

        val won = (service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
            as TowerPlayBattleCompletionResult.Completed).state

        assertEquals(TowerPlayPhase.TEAM_LOCKED, won.phase)
        assertEquals(8, won.currentWinStreak)
        assertTrue(won.runStarted)
        // The registered team stays: a refresh does not swap it for the adventure party.
        assertEquals(won, service.refreshParty(playerId, party(offset = 10)))
    }

    @Test
    fun `giving up between battles ends the streak and opens the rules`() {
        val service = service()
        start(service, service.open(playerId, request(currentWinStreak = 7)))
        val won = (service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
            as TowerPlayBattleCompletionResult.Completed).state
        val changed = party(offset = 10)

        val retired = accepted(service.mutate(playerId, TowerPlayIntent.Retire(next(), contextId, won.revision), changed))

        assertEquals(listOf(track), retirements)
        assertEquals(TowerPlayPhase.SELECTING, retired.phase)
        assertEquals(0, retired.currentWinStreak)
        assertEquals(8, retired.bestWinStreak)
        assertFalse(retired.runStarted)
        assertEquals(changed, retired.party)
        assertEquals(0, service.progress(playerId)?.getValue(track)?.currentWinStreak)
        assertEquals(8, service.progress(playerId)?.getValue(track)?.bestWinStreak)
    }

    @Test
    fun `giving up is open after a team change between battles but not before the run begins or during a battle`() {
        val service = service()
        val opened = service.open(playerId, request(currentWinStreak = 0))
        val fresh = service.mutate(playerId, TowerPlayIntent.Retire(next(), contextId, opened.revision))
        assertEquals(TowerPlayMessageKeys.NOTHING_TO_RETIRE, (fresh as TowerPlayMutationResult.Rejected).messageKey)

        val active = start(service, opened)
        val during = service.mutate(playerId, TowerPlayIntent.Retire(next(), contextId, active.revision))
        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, (during as TowerPlayMutationResult.Rejected).messageKey)

        val won = (service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
            as TowerPlayBattleCompletionResult.Completed).state
        val changingTeam = accepted(service.mutate(playerId, TowerPlayIntent.ChangeTeam(next(), contextId, won.revision)))
        assertEquals(TowerPlayPhase.CHANGING_TEAM, changingTeam.phase)
        assertTrue(TowerPlayInteractionPolicy.canRetire(changingTeam))
        val retired = accepted(service.mutate(playerId, TowerPlayIntent.Retire(next(), contextId, changingTeam.revision)))
        assertEquals(0, retired.currentWinStreak)
        assertFalse(TowerPlayInteractionPolicy.canRetire(retired))
        assertEquals(listOf(track), retirements)
    }

    @Test
    fun `leaving between battles ends every streak the session held`() {
        val service = service()
        start(service, service.open(playerId, request(currentWinStreak = 7)))
        val won = (service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
            as TowerPlayBattleCompletionResult.Completed).state
        assertTrue(retirements.isEmpty())

        assertTrue(service.disconnect(playerId))

        // Singles and Doubles both stood at a streak; neither can go on with another six.
        assertEquals(setOf(track, TowerTrack(TowerBattleFormat.DOUBLE, TowerMode.ENDLESS)), retirements.toSet())
        assertEquals(TowerPlayPhase.TEAM_LOCKED, won.phase)
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `closing a session between battles ends its streak, and a loss leaves nothing to end`() {
        val service = service()
        service.open(playerId, request(currentWinStreak = 0))
        val active = start(service, service.current(playerId)!!)
        service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)

        assertEquals(TowerSessionAbandonResult.SessionClosed, service.abandonSession(playerId) { true })
        assertEquals(listOf(track), retirements)

        retirements.clear()
        service.open(playerId, request(currentWinStreak = 0))
        start(service, service.current(playerId)!!)
        assertTrue(active.runStarted)
        assertTrue(service.disconnect(playerId))
        // The battle under way ended as a loss, which already ended the streak.
        assertTrue(retirements.isEmpty())
    }

    private fun service() = TowerPlaySessionService(
        battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
        registeredTeamSnapshots = object : TowerRegisteredTeamSnapshots {
            override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult {
                snapshots += team.members.map { it.pokemonId }
                return TowerRegisteredTeamSnapshotResult.Stored
            }

            override fun discard(playerId: UUID) = Unit
        },
        entryContextIdFactory = { contextId },
        runRetirementSink = { _, retired -> retirements += retired },
    )

    private fun lock(service: TowerPlaySessionService, opened: TowerPlayViewState): TowerPlayViewState {
        var state = opened
        state.party.sortedBy(TowerPlayPartySlot::slot).take(3).forEach { slot ->
            state = accepted(service.mutate(playerId, TowerPlayIntent.ToggleSelection(next(), contextId, state.revision, slot.pokemonId)))
        }
        return accepted(service.mutate(playerId, TowerPlayIntent.LockTeam(next(), contextId, state.revision), state.party))
    }

    private fun start(service: TowerPlaySessionService, opened: TowerPlayViewState): TowerPlayViewState {
        val state = lock(service, opened)
        return accepted(service.mutate(playerId, TowerPlayIntent.Start(next(), contextId, state.revision)))
    }

    private fun accepted(result: TowerPlayMutationResult): TowerPlayViewState =
        (result as TowerPlayMutationResult.Accepted).state

    private fun next() = UUID(7, ++requestIds)

    private fun request(currentWinStreak: Int) = TowerPlayOpenRequest(
        party = party(),
        initialFormat = TowerBattleFormat.SINGLE,
        progressByTrack = clearedNormalWith(TowerBattleFormat.entries.associateWith { format ->
            TowerProgress(format, currentWinStreak, currentWinStreak)
        }),
        bpBalance = 0,
    )

    private fun party(offset: Int = 0): List<TowerPlayPartySlot> = (1..6).map { index ->
        TowerPlayPartySlot(
            slot = index - 1,
            pokemonId = UUID(0, (offset + index).toLong()),
            speciesId = "cobblemon:species_${offset + index}",
            heldItemId = "minecraft:item_${offset + index}",
            level = 50,
            battleLevel = 50,
            formId = null,
        )
    }
}
