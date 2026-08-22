package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchRequest
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgressUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TowerPlayBattleLaunchTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val contextId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val battleId = UUID.fromString("22222222-3333-4444-5555-666666666666")

    @Test
    fun `legendary class option reaches the launcher and locks after the first battle starts`() {
        val launches = ArrayList<TowerBattleLaunchRequest>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { request ->
                launches += request
                TowerBattleLaunchResult.Started(battleId)
            },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        var state = service.open(playerId, request())
        state = (service.mutate(
            playerId,
            TowerPlayIntent.ChangeLegendaryClassAllowed(UUID(9, 1), contextId, state.revision, true),
        ) as TowerPlayMutationResult.Accepted).state
        state = (service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(9, 2), contextId, state.revision, MajorBattleMechanic.MEGA),
        ) as TowerPlayMutationResult.Accepted).state
        party().take(3).forEachIndexed { index, pokemon ->
            state = (service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(9, 3L + index), contextId, state.revision, pokemon.pokemonId),
            ) as TowerPlayMutationResult.Accepted).state
        }
        state = (service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(9, 7), contextId, state.revision),
        ) as TowerPlayMutationResult.Accepted).state

        val active = (service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(9, 8), contextId, state.revision),
        ) as TowerPlayMutationResult.Accepted).state

        assertTrue(launches.single().legendaryClassAllowed)
        assertTrue(active.legendaryClassAllowed)
        assertTrue(active.legendaryClassLocked)
    }

    @Test
    fun `successful start launches the locked team and enters active phase`() {
        val launches = ArrayList<TowerBattleLaunchRequest>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { request ->
                launches += request
                TowerBattleLaunchResult.Started(battleId)
            },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        val locked = lockFirstThree(service)
        val intent = TowerPlayIntent.Start(UUID(0, 20), contextId, locked.revision)

        val first = service.mutate(playerId, intent) as TowerPlayMutationResult.Accepted
        val duplicate = service.mutate(playerId, intent)

        assertEquals(TowerPlayPhase.ACTIVE, first.state.phase)
        assertEquals(locked.revision + 1, first.state.revision)
        assertEquals(battleId, service.activeBattleId(playerId))
        assertEquals(first, duplicate)
        assertEquals(1, launches.size)
        assertEquals(playerId, launches.single().playerId)
        assertEquals(TowerBattleFormat.SINGLE, launches.single().progress.format)
        assertEquals(party().take(3).map(TowerPlayPartySlot::pokemonId), launches.single().selection.members.map { it.pokemonId })
    }

    @Test
    fun `successful start uses the order in which pokemon were selected`() {
        val launches = ArrayList<TowerBattleLaunchRequest>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { request ->
                launches += request
                TowerBattleLaunchResult.Started(battleId)
            },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        var state = service.open(playerId, request())
        state = (service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(0, 9), contextId, state.revision, MajorBattleMechanic.MEGA),
        ) as TowerPlayMutationResult.Accepted).state
        val selectedInClickOrder = listOf(party()[2], party()[0], party()[1])
        selectedInClickOrder.forEachIndexed { index, pokemon ->
            state = (service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(1, index.toLong()), contextId, state.revision, pokemon.pokemonId),
            ) as TowerPlayMutationResult.Accepted).state
        }
        val locked = (service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(1, 4), contextId, state.revision),
        ) as TowerPlayMutationResult.Accepted).state

        service.mutate(playerId, TowerPlayIntent.Start(UUID(1, 5), contextId, locked.revision))

        assertEquals(
            selectedInClickOrder.map(TowerPlayPartySlot::pokemonId),
            launches.single().selection.members.map { it.pokemonId },
        )
    }

    @Test
    fun `failed launch keeps the locked phase and does not advance revision`() {
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Unavailable },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        val locked = lockFirstThree(service)

        val result = service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 21), contextId, locked.revision),
        )

        result as TowerPlayMutationResult.Rejected
        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, result.messageKey)
        assertEquals(TowerPlayPhase.TEAM_LOCKED, service.current(playerId)?.phase)
        assertEquals(locked.revision, service.current(playerId)?.revision)
        assertEquals(null, service.activeBattleId(playerId))
    }

    @Test
    fun `active battle cannot be discarded by the preparation abandon path`() {
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        val locked = lockFirstThree(service)
        val active = (service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 23), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted).state

        val result = service.mutate(
            playerId,
            TowerPlayIntent.Abandon(UUID(0, 24), contextId, active.revision),
        )

        result as TowerPlayMutationResult.Rejected
        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, result.messageKey)
        assertEquals(TowerPlayPhase.ACTIVE, service.current(playerId)?.phase)
        assertEquals(battleId, service.activeBattleId(playerId))
    }

    @Test
    fun `explicit active session abandon forfeits once then records a loss and closes the session`() {
        val recorded = ArrayList<TowerProgressUpdate>()
        val forfeits = ArrayList<UUID>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> recorded += update },
        )
        val locked = lockFirstThree(service)
        service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 25), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted

        val first = service.abandonSession(playerId) { requestedBattleId ->
            forfeits += requestedBattleId
            true
        }
        val duplicate = service.abandonSession(playerId) { requestedBattleId ->
            forfeits += requestedBattleId
            true
        }
        val completed = service.completeBattle(playerId, battleId, TowerBattleOutcome.LOSS)

        assertEquals(TowerSessionAbandonResult.ForfeitRequested(battleId), first)
        assertEquals(first, duplicate)
        assertEquals(listOf(battleId), forfeits)
        assertEquals(TowerPlayBattleCompletionResult.SessionAbandoned, completed)
        assertEquals(1, recorded.size)
        assertEquals(TowerBattleOutcome.LOSS, recorded.single().outcome)
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `explicit abandon remains a loss when the battle ends without a declared winner`() {
        val recorded = ArrayList<TowerProgressUpdate>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> recorded += update },
        )
        val locked = lockFirstThree(service)
        service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 26), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted
        service.abandonSession(playerId) { true }

        val completed = service.cancelBattle(playerId, battleId)

        assertEquals(TowerPlayBattleCompletionResult.SessionAbandoned, completed)
        assertEquals(1, recorded.size)
        assertEquals(TowerBattleOutcome.LOSS, recorded.single().outcome)
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `explicit abandon overrides a racing win callback with the promised loss`() {
        val recorded = ArrayList<TowerProgressUpdate>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> recorded += update },
        )
        val locked = lockFirstThree(service)
        service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 27), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted
        service.abandonSession(playerId) { true }

        service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)

        assertEquals(1, recorded.size)
        assertEquals(TowerBattleOutcome.LOSS, recorded.single().outcome)
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `battle completion records once and keeps the locked session ready for the next floor`() {
        val recorded = ArrayList<TowerProgressUpdate>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> recorded += update },
        )
        val locked = lockFirstThree(service)
        val active = (service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 30), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted).state

        val completed = service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
        val duplicate = service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)

        completed as TowerPlayBattleCompletionResult.Completed
        assertEquals(TowerPlayPhase.TEAM_LOCKED, completed.state.phase)
        assertEquals(active.revision + 1, completed.state.revision)
        assertEquals(1, completed.state.currentWinStreak)
        assertTrue(completed.state.mechanicLocked)
        assertEquals(null, service.activeBattleId(playerId))
        assertEquals(TowerPlayBattleCompletionResult.NoActiveBattle, duplicate)
        assertEquals(1, recorded.size)
    }

    @Test
    fun `record failure leaves active battle state unchanged instead of advancing only memory progress`() {
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, _ -> error("record unavailable") },
        )
        val locked = lockFirstThree(service)
        val active = (service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 31), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted).state

        assertThrows(IllegalStateException::class.java) {
            service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
        }

        assertEquals(active, service.current(playerId))
        assertEquals(battleId, service.activeBattleId(playerId))
    }

    @Test
    fun `no contest clears the ended battle without changing progress or recording a result`() {
        var records = 0
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, _ -> records++ },
        )
        val locked = lockFirstThree(service)
        val active = (service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 32), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted).state

        val cancelled = service.cancelBattle(playerId, battleId)

        cancelled as TowerPlayBattleCompletionResult.Completed
        assertEquals(TowerPlayPhase.TEAM_LOCKED, cancelled.state.phase)
        assertEquals(active.revision + 1, cancelled.state.revision)
        assertEquals(active.currentWinStreak, cancelled.state.currentWinStreak)
        assertEquals(0, records)
        assertEquals(null, service.activeBattleId(playerId))
    }

    @Test
    fun `disconnect during an active battle records a loss before closing the session`() {
        val recorded = ArrayList<TowerProgressUpdate>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> recorded += update },
        )
        val locked = lockFirstThree(service, currentWinStreak = 7)
        service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 34), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted

        assertTrue(service.disconnect(playerId))

        assertEquals(1, recorded.size)
        assertEquals(TowerBattleOutcome.LOSS, recorded.single().outcome)
        assertEquals(0, recorded.single().after.currentWinStreak)
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `production completion can supply the persistence boundary used by the atomic state transition`() {
        val constructorRecords = ArrayList<TowerProgressUpdate>()
        val productionRecords = ArrayList<TowerProgressUpdate>()
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleCompletionSink = { _, update -> constructorRecords += update },
        )
        val locked = lockFirstThree(service)
        service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 33), contextId, locked.revision),
        ) as TowerPlayMutationResult.Accepted

        service.completeBattle(
            playerId,
            battleId,
            TowerBattleOutcome.WIN,
            TowerPlayBattleCompletionSink { _, update -> productionRecords += update },
        )

        assertTrue(constructorRecords.isEmpty())
        assertEquals(1, productionRecords.size)
    }

    @Test
    fun `start before locking is rejected without calling launcher`() {
        var launches = 0
        val service = TowerPlaySessionService(
            entryContextIdFactory = { contextId },
            battleLauncher = TowerBattleLauncher {
                launches++
                TowerBattleLaunchResult.Started(battleId)
            },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        )
        val state = service.open(playerId, request())

        val result = service.mutate(
            playerId,
            TowerPlayIntent.Start(UUID(0, 22), contextId, state.revision),
        )

        result as TowerPlayMutationResult.Rejected
        assertEquals(TowerPlayMessageKeys.PHASE_INVALID, result.messageKey)
        assertEquals(0, launches)
    }

    private fun lockFirstThree(
        service: TowerPlaySessionService,
        currentWinStreak: Int = 0,
    ): TowerPlayViewState {
        var state = service.open(playerId, request(currentWinStreak))
        state = (service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(0, 9), contextId, state.revision, MajorBattleMechanic.MEGA),
        ) as TowerPlayMutationResult.Accepted).state
        party().take(3).forEachIndexed { index, pokemon ->
            state = (service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(0, index.toLong() + 1), contextId, state.revision, pokemon.pokemonId),
            ) as TowerPlayMutationResult.Accepted).state
        }
        val locked = service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(0, 10), contextId, state.revision),
        )
        assertTrue(locked is TowerPlayMutationResult.Accepted)
        return (locked as TowerPlayMutationResult.Accepted).state
    }

    private fun request(currentWinStreak: Int = 0) = TowerPlayOpenRequest(
        party = party(),
        initialFormat = TowerBattleFormat.SINGLE,
        progressByFormat = TowerBattleFormat.entries.associateWith { format ->
            TowerProgress(format, currentWinStreak, currentWinStreak)
        },
        bpBalance = 0,
    )

    private fun party(): List<TowerPlayPartySlot> = (1..6).map { index ->
        TowerPlayPartySlot(
            slot = index - 1,
            pokemonId = UUID(0, index.toLong()),
            speciesId = "cobblemon:species_$index",
            heldItemId = if (index == 6) null else "minecraft:item_$index",
            level = 40 + index,
            battleLevel = minOf(40 + index, 50),
        )
    }
}
