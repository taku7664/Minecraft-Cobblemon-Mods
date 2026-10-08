package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.tower.clearedNormalWith
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.mcc.internal.tower.TowerBattleLauncher
import jbro.cobblemon.mcc.internal.tower.TowerBattleOutcome
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeam
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPlaySessionServiceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val entryContextId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val requestId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")
    private val pokemonId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")

    @Test
    fun `open creates server-owned state from party progress and BP`() {
        val service = service()

        val state = service.open(
            playerId,
            TowerPlayOpenRequest(
                party = validParty(),
                initialFormat = TowerBattleFormat.DOUBLE,
                progressByTrack = clearedNormalWith(mapOf(
                    TowerBattleFormat.SINGLE to TowerProgress.initial(TowerBattleFormat.SINGLE),
                    TowerBattleFormat.DOUBLE to TowerProgress(TowerBattleFormat.DOUBLE, 7, 9),
                )),
                bpBalance = 37,
            ),
        )

        assertEquals(entryContextId, state.entryContextId)
        assertEquals(0, state.revision)
        assertEquals(TowerBattleFormat.DOUBLE, state.format)
        assertEquals(TowerPlayPhase.SELECTING, state.phase)
        assertEquals(6, state.party.size)
        assertEquals(jbro.cobblemon.mcc.internal.tower.TowerStreakStage.PRACTICAL, state.streakStage)
        assertEquals(7, state.currentWinStreak)
        assertEquals(9, state.bestWinStreak)
        assertEquals(3, state.bpPerWin)
        assertEquals(37, state.bpBalance)
        assertTrue(state.errorKeys.isEmpty())
    }

    @Test
    fun `boss preview includes the five bp bonus`() {
        val state = service().open(
            playerId,
            TowerPlayOpenRequest(
                party = validParty(),
                initialFormat = TowerBattleFormat.SINGLE,
                progressByTrack = clearedNormalWith(mapOf(
                    TowerBattleFormat.SINGLE to TowerProgress(TowerBattleFormat.SINGLE, 4, 4),
                    TowerBattleFormat.DOUBLE to TowerProgress.initial(TowerBattleFormat.DOUBLE),
                )),
                bpBalance = 0,
            ),
        )

        assertEquals(7, state.bpPerWin)
    }

    @Test
    fun `admin streak setter refreshes the matching open format`() {
        val service = service()
        service.open(playerId, openRequest())

        val updated = service.adminSetProgress(
            playerId,
            TowerProgress(TowerBattleFormat.SINGLE, currentWinStreak = 14, bestWinStreak = 20),
        )

        assertTrue(updated)
        assertEquals(14, service.current(playerId)?.currentWinStreak)
        assertEquals(20, service.current(playerId)?.bestWinStreak)
        assertEquals(14, service.progress(playerId)?.getValue(TowerTrack(TowerBattleFormat.SINGLE, TowerMode.ENDLESS))?.currentWinStreak)
    }

    @Test
    fun `verified terminal context becomes the new screen context`() {
        val verifiedContextId = UUID.fromString("12345678-2222-3333-4444-555555555555")
        val entryContext = TowerPlayEntryContext.VerifiedTerminal(
            entryContextId = verifiedContextId,
            terminalId = UUID.fromString("abcdefab-2222-3333-4444-555555555555"),
            dimensionId = "minecraft:overworld",
            x = 10,
            y = 64,
            z = -4,
        )
        val service = service()

        val state = service.open(playerId, openRequest(), entryContext)

        assertEquals(verifiedContextId, state.entryContextId)
        assertEquals(entryContext, service.entryContext(playerId))
    }

    @Test
    fun `toggle and lock are applied only by the server`() {
        val service = service()
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        validParty().take(3).forEachIndexed { index, pokemon ->
            state = accepted(
                service.mutate(
                    playerId,
                    TowerPlayIntent.ToggleSelection(
                        UUID(0, index.toLong() + 1),
                        entryContextId,
                        state.revision,
                        pokemon.pokemonId,
                    ),
                ),
            )
        }

        val locked = accepted(
            service.mutate(
                playerId,
                TowerPlayIntent.LockTeam(requestId, entryContextId, state.revision),
            ),
        )

        assertEquals(TowerPlayPhase.TEAM_LOCKED, locked.phase)
        assertEquals(5, locked.revision)
        assertEquals(3, locked.selectedPokemonIds.size)
    }

    @Test
    fun `stale revision and previous screen context are rejected without mutation`() {
        var nextContext = 0L
        val service = TowerPlaySessionService(
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
        ) { UUID(0, ++nextContext) }
        val first = service.open(playerId, openRequest())
        service.close(playerId)
        val current = service.open(playerId, openRequest())

        val oldContext = rejected(
            service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(requestId, first.entryContextId, 0, pokemonId),
            ),
        )
        val staleRevision = rejected(
            service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(0, 9), current.entryContextId, 1, pokemonId),
            ),
        )

        assertNotEquals(first.entryContextId, current.entryContextId)
        assertEquals(TowerPlayMessageKeys.SESSION_NOT_FOUND, oldContext.messageKey)
        assertEquals(TowerPlayMessageKeys.STALE_REVISION, staleRevision.messageKey)
        assertEquals(0, service.current(playerId)?.revision)
        assertTrue(service.current(playerId)?.selectedPokemonIds.orEmpty().isEmpty())
    }

    @Test
    fun `reopening an existing session preserves its server-owned registration state`() {
        var snapshots = 0
        var discards = 0
        val snapshotStore = object : TowerRegisteredTeamSnapshots {
            override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult {
                snapshots++
                return TowerRegisteredTeamSnapshotResult.Stored
            }

            override fun discard(playerId: UUID) {
                discards++
            }
        }
        val service = TowerPlaySessionService(
            registeredTeamSnapshots = snapshotStore,
            entryContextIdFactory = { entryContextId },
        )
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        validParty().take(3).forEachIndexed { index, pokemon ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(
                    UUID(0, index.toLong() + 1),
                    entryContextId,
                    state.revision,
                    pokemon.pokemonId,
                ),
            ))
        }
        val locked = accepted(service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(requestId, entryContextId, state.revision),
        ))

        val reopened = service.open(
            playerId,
            TowerPlayOpenRequest(
                party = validParty().reversed(),
                initialFormat = TowerBattleFormat.DOUBLE,
                progressByTrack = progressByTrack(),
                bpBalance = 999,
            ),
        )

        assertEquals(locked, reopened)
        assertEquals(1, snapshots)
        assertEquals(1, discards)
    }

    @Test
    fun `reopening an unlocked session refreshes the current adventure party`() {
        val service = TowerPlaySessionService(entryContextIdFactory = { entryContextId })
        val first = service.open(playerId, openRequest(party = validParty().take(1)))
        val refreshedContext = UUID.randomUUID()

        val reopened = service.open(
            playerId,
            openRequest(party = validParty()),
            TowerPlayEntryContext.Command(refreshedContext),
        )

        assertEquals(TowerPlayPhase.SELECTING, reopened.phase)
        assertEquals(6, reopened.party.size)
        assertEquals(refreshedContext, reopened.entryContextId)
        assertNotEquals(first.entryContextId, reopened.entryContextId)
        assertTrue(reopened.selectedPokemonIds.isEmpty())
    }

    @Test
    fun `changing team before the first battle lets the registration go and opens the six and the rules`() {
        var snapshots = 0
        val service = TowerPlaySessionService(
            registeredTeamSnapshots = countingSnapshots({ snapshots++ }, {}),
            entryContextIdFactory = { entryContextId },
        )
        val locked = lockEntries(service, service.open(playerId, openRequest()))
        val changedAdventureParty = replacementParty()

        val released = accepted(service.mutate(
            playerId,
            TowerPlayIntent.ChangeTeam(UUID(11, 1), entryContextId, locked.revision),
            changedAdventureParty,
        ))
        // No battle was fought: Retire has nothing to end, the new party shows and the rules open again.
        val format = accepted(service.mutate(
            playerId,
            TowerPlayIntent.ChangeFormat(UUID(11, 2), entryContextId, released.revision, TowerBattleFormat.DOUBLE),
        ))
        val reopened = service.open(playerId, openRequest(changedAdventureParty))
        val relocked = lockEntries(service, reopened)

        assertEquals(TowerPlayPhase.SELECTING, released.phase)
        assertEquals(changedAdventureParty, released.party)
        assertTrue(released.selectedPokemonIds.isEmpty())
        assertFalse(released.runStarted)
        assertFalse(TowerPlayInteractionPolicy.canRetire(released))
        assertEquals(TowerBattleFormat.DOUBLE, format.format)
        assertEquals(changedAdventureParty, reopened.party)
        // The next lock registers the new six, replacing the released snapshot.
        assertEquals(TowerPlayPhase.TEAM_LOCKED, relocked.phase)
        assertEquals(2, snapshots)
    }

    @Test
    fun `changing team during a run picks again from the registered six with the run's rules kept`() {
        var snapshots = 0
        var discards = 0
        val battleId = UUID(12, 99)
        val service = TowerPlaySessionService(
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = countingSnapshots({ snapshots++ }, { discards++ }),
            entryContextIdFactory = { entryContextId },
        )
        val registeredParty = validParty()
        val locked = lockEntries(service, service.open(playerId, openRequest(registeredParty)))
        val active = accepted(service.mutate(playerId, TowerPlayIntent.Start(UUID(12, 1), entryContextId, locked.revision)))
        val won = (service.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
            as TowerPlayBattleCompletionResult.Completed).state
        val discardsBeforeChange = discards

        var state = accepted(service.mutate(
            playerId,
            TowerPlayIntent.ChangeTeam(UUID(12, 2), entryContextId, won.revision),
            replacementParty(),
        ))
        val changing = state
        val format = rejected(service.mutate(
            playerId,
            TowerPlayIntent.ChangeFormat(UUID(12, 3), entryContextId, state.revision, TowerBattleFormat.DOUBLE),
        ))
        val mode = rejected(service.mutate(
            playerId,
            TowerPlayIntent.ChangeMode(UUID(12, 4), entryContextId, state.revision, TowerMode.NORMAL),
        ))
        val mechanic = rejected(service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID(12, 5), entryContextId, state.revision, MajorBattleMechanic.TERA),
        ))
        // Reopening the screen between battles keeps the run and its screen context.
        assertSame(state, service.open(playerId, openRequest(replacementParty())))
        registeredParty.take(3).forEachIndexed { index, pokemon ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(12, 10L + index), entryContextId, state.revision, pokemon.pokemonId),
            ))
        }
        registeredParty.takeLast(3).forEachIndexed { index, pokemon ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID(12, 20L + index), entryContextId, state.revision, pokemon.pokemonId),
            ))
        }
        // The registered six stay, so a changed adventure party does not stop the lock.
        val relocked = accepted(service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(UUID(12, 30), entryContextId, state.revision),
            replacementParty(),
        ))

        assertTrue(active.runStarted)
        assertEquals(TowerPlayPhase.CHANGING_TEAM, changing.phase)
        assertEquals(registeredParty, changing.party)
        assertEquals(won.selectedPokemonOrder, changing.selectedPokemonOrder)
        assertTrue(TowerPlayInteractionPolicy.canRetire(changing))
        assertEquals(TowerPlayMessageKeys.PHASE_INVALID, format.messageKey)
        assertEquals(TowerPlayMessageKeys.PHASE_INVALID, mode.messageKey)
        assertEquals(TowerPlayMessageKeys.PHASE_INVALID, mechanic.messageKey)
        assertEquals(TowerPlayPhase.TEAM_LOCKED, relocked.phase)
        assertEquals(registeredParty.takeLast(3).map { it.pokemonId }, relocked.selectedPokemonOrder)
        assertEquals(1, snapshots)
        assertEquals(discardsBeforeChange, discards)
    }

    @Test
    fun `forfeiting from the screen ends the battle as a loss and the run with it`() {
        val battleId = UUID(13, 99)
        val forfeits = ArrayList<UUID>()
        val service = TowerPlaySessionService(
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleForfeit = { _, battle -> forfeits += battle; true },
            entryContextIdFactory = { entryContextId },
        )
        val locked = lockEntries(service, service.open(playerId, openRequest()))
        val active = accepted(service.mutate(playerId, TowerPlayIntent.Start(UUID(13, 1), entryContextId, locked.revision)))

        val requested = accepted(service.mutate(playerId, TowerPlayIntent.Forfeit(UUID(13, 2), entryContextId, active.revision)))
        val again = accepted(service.mutate(playerId, TowerPlayIntent.Forfeit(UUID(13, 3), entryContextId, requested.revision)))
        // However the battle reports its end, a forfeited one is a loss.
        val ended = (service.cancelBattle(playerId, battleId) as TowerPlayBattleCompletionResult.Completed).state

        assertEquals(listOf(battleId), forfeits)
        assertEquals(TowerPlayPhase.ACTIVE, again.phase)
        assertEquals(TowerPlayPhase.SELECTING, ended.phase)
        assertFalse(ended.runStarted)
        assertEquals(0, ended.currentWinStreak)
        assertEquals(entryContextId, service.current(playerId)?.entryContextId)
    }

    @Test
    fun `a forfeit the battle cannot take is rejected and leaves the battle running`() {
        val battleId = UUID(14, 99)
        val service = TowerPlaySessionService(
            battleLauncher = TowerBattleLauncher { TowerBattleLaunchResult.Started(battleId) },
            registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
            battleForfeit = { _, _ -> false },
            entryContextIdFactory = { entryContextId },
        )
        val locked = lockEntries(service, service.open(playerId, openRequest()))
        val active = accepted(service.mutate(playerId, TowerPlayIntent.Start(UUID(14, 1), entryContextId, locked.revision)))

        val result = rejected(service.mutate(playerId, TowerPlayIntent.Forfeit(UUID(14, 2), entryContextId, active.revision)))
        // The battle was never given up, so a cancelled one keeps the run.
        val cancelled = (service.cancelBattle(playerId, battleId) as TowerPlayBattleCompletionResult.Completed).state

        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, result.messageKey)
        assertEquals(TowerPlayPhase.TEAM_LOCKED, cancelled.phase)
        assertTrue(cancelled.runStarted)
    }

    @Test
    fun `duplicate request returns the same accepted result without a second toggle`() {
        val service = service()
        service.open(playerId, openRequest())
        val intent = TowerPlayIntent.ToggleSelection(requestId, entryContextId, 0, pokemonId)

        val first = service.mutate(playerId, intent)
        val duplicate = service.mutate(playerId, intent)

        assertEquals(first, duplicate)
        assertEquals(setOf(pokemonId), service.current(playerId)?.selectedPokemonIds)
        assertEquals(1, service.current(playerId)?.revision)
    }

    @Test
    fun `same request id with different intent is rejected as conflict`() {
        val service = service()
        service.open(playerId, openRequest())
        service.mutate(
            playerId,
            TowerPlayIntent.ToggleSelection(requestId, entryContextId, 0, pokemonId),
        )

        val conflict = rejected(
            service.mutate(
                playerId,
                TowerPlayIntent.ChangeFormat(requestId, entryContextId, 1, TowerBattleFormat.DOUBLE),
            ),
        )

        assertEquals(TowerPlayMessageKeys.REQUEST_CONFLICT, conflict.messageKey)
        assertEquals(TowerBattleFormat.SINGLE, service.current(playerId)?.format)
    }

    @Test
    fun `format change clears selection and increments revision`() {
        val service = service()
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        state = accepted(service.mutate(
            playerId,
            TowerPlayIntent.ToggleSelection(UUID(0, 1), entryContextId, state.revision, pokemonId),
        ))

        val changed = accepted(
            service.mutate(
                playerId, TowerPlayIntent.ChangeFormat(requestId, entryContextId, state.revision, TowerBattleFormat.DOUBLE),
            ),
        )

        assertEquals(TowerBattleFormat.DOUBLE, changed.format)
        assertEquals(7, changed.currentWinStreak)
        assertEquals(9, changed.bestWinStreak)
        assertTrue(changed.selectedPokemonIds.isEmpty())
        assertEquals(MajorBattleMechanic.MEGA, changed.selectedMechanic)
        assertEquals(3, changed.revision)
    }

    @Test
    fun `invalid registered party remains visible but cannot be locked`() {
        val service = service()
        val invalidParty = validParty().dropLast(1)
        val state = service.open(
            playerId,
            TowerPlayOpenRequest(
                invalidParty,
                TowerBattleFormat.SINGLE,
                progressByTrack(),
                0,
            ),
        )
        val stateWithMechanic = selectMechanic(service, state)

        val rejected = rejected(
            service.mutate(
                playerId,
                TowerPlayIntent.LockTeam(requestId, entryContextId, stateWithMechanic.revision),
            ),
        )

        assertEquals(listOf(TowerPlayMessageKeys.PARTY_SIZE), state.errorKeys)
        assertEquals(TowerPlayMessageKeys.TEAM_INVALID, rejected.messageKey)
        assertEquals(TowerPlayPhase.SELECTING, service.current(playerId)?.phase)
    }

    @Test
    fun `start stays locked while battle launcher is unavailable`() {
        val service = service()
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        validParty().take(3).forEachIndexed { index, pokemon ->
            state = accepted(
                service.mutate(
                    playerId,
                    TowerPlayIntent.ToggleSelection(UUID(0, index.toLong() + 1), entryContextId, state.revision, pokemon.pokemonId),
                ),
            )
        }
        state = accepted(
            service.mutate(playerId, TowerPlayIntent.LockTeam(UUID(0, 10), entryContextId, state.revision)),
        )

        val result = rejected(
            service.mutate(playerId, TowerPlayIntent.Start(requestId, entryContextId, state.revision)),
        )

        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, result.messageKey)
        assertEquals(TowerPlayPhase.TEAM_LOCKED, service.current(playerId)?.phase)
        assertEquals(state.revision, service.current(playerId)?.revision)
    }

    @Test
    fun `lock rejects a party that changed after the screen opened`() {
        val service = service()
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        validParty().take(3).forEachIndexed { index, pokemon ->
            state = accepted(
                service.mutate(
                    playerId,
                    TowerPlayIntent.ToggleSelection(UUID(0, index.toLong() + 1), entryContextId, state.revision, pokemon.pokemonId),
                ),
            )
        }
        val changedParty = validParty().mapIndexed { index, slot ->
            if (index == 0) slot.copy(level = slot.level + 1, battleLevel = slot.battleLevel + 1) else slot
        }

        val result = rejected(
            service.mutate(
                playerId,
                TowerPlayIntent.LockTeam(requestId, entryContextId, state.revision),
                changedParty,
            ),
        )

        assertEquals(TowerPlayMessageKeys.PARTY_CHANGED, result.messageKey)
        assertEquals(TowerPlayPhase.SELECTING, service.current(playerId)?.phase)
    }

    @Test
    fun `lock remains selecting when the complete registered team cannot be snapshotted`() {
        val unavailableSnapshots = object : TowerRegisteredTeamSnapshots {
            override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult =
                TowerRegisteredTeamSnapshotResult.Rejected("clone_failed")

            override fun discard(playerId: UUID) = Unit
        }
        val service = TowerPlaySessionService(
            registeredTeamSnapshots = unavailableSnapshots,
        ) { entryContextId }
        var state = service.open(playerId, openRequest())
        state = selectMechanic(service, state)
        validParty().take(3).forEachIndexed { index, pokemon ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(
                    UUID(0, index.toLong() + 1),
                    entryContextId,
                    state.revision,
                    pokemon.pokemonId,
                ),
            ))
        }

        val result = rejected(service.mutate(
            playerId,
            TowerPlayIntent.LockTeam(requestId, entryContextId, state.revision),
        ))

        assertEquals(TowerPlayMessageKeys.BATTLE_UNAVAILABLE, result.messageKey)
        assertEquals(TowerPlayPhase.SELECTING, service.current(playerId)?.phase)
        assertEquals(state.revision, service.current(playerId)?.revision)
    }

    @Test
    fun `closing removes the player's in-memory preparation session`() {
        val service = service()
        service.open(playerId, openRequest())

        assertTrue(service.close(playerId))
        assertEquals(null, service.current(playerId))
        assertTrue(!service.close(playerId))
    }

    @Test
    fun `snapshot cleanup failure cannot keep a closed tower session alive`() {
        val failure = NoSuchMethodError("snapshot API drift")
        var failDiscard = false
        val snapshots = object : TowerRegisteredTeamSnapshots {
            override fun snapshot(playerId: UUID, team: TowerRegisteredTeam) =
                TowerRegisteredTeamSnapshotResult.Stored

            override fun discard(playerId: UUID) {
                if (failDiscard) throw failure
            }
        }
        val service = TowerPlaySessionService(registeredTeamSnapshots = snapshots) { entryContextId }
        service.open(playerId, openRequest())
        failDiscard = true

        assertSame(failure, assertThrows(NoSuchMethodError::class.java) { service.close(playerId) })
        assertEquals(null, service.current(playerId))
    }

    @Test
    fun `server reset clears every tower session even when one snapshot cleanup fails`() {
        val otherPlayerId = UUID.randomUUID()
        val failure = NoSuchMethodError("snapshot API drift")
        val discarded = ArrayList<UUID>()
        var failDiscard = false
        val snapshots = object : TowerRegisteredTeamSnapshots {
            override fun snapshot(playerId: UUID, team: TowerRegisteredTeam) =
                TowerRegisteredTeamSnapshotResult.Stored

            override fun discard(playerId: UUID) {
                discarded += playerId
                if (failDiscard && playerId == this@TowerPlaySessionServiceTest.playerId) throw failure
            }
        }
        var nextContextId = 1L
        val service = TowerPlaySessionService(registeredTeamSnapshots = snapshots) {
            UUID(0, nextContextId++)
        }
        service.open(playerId, openRequest())
        service.open(otherPlayerId, openRequest())
        discarded.clear()
        failDiscard = true

        assertSame(failure, assertThrows(NoSuchMethodError::class.java, service::clear))

        assertEquals(null, service.current(playerId))
        assertEquals(null, service.current(otherPlayerId))
        assertEquals(setOf(playerId, otherPlayerId), discarded.toSet())
    }

    @Test
    fun `authoritative BP refresh is retained by later session states`() {
        val service = service()
        val opened = service.open(playerId, openRequest())

        val refreshed = service.refreshBpBalance(playerId, 17)
        val changed = service.mutate(
            playerId,
            TowerPlayIntent.ChangeFormat(UUID.randomUUID(), entryContextId, opened.revision, TowerBattleFormat.DOUBLE),
        ) as TowerPlayMutationResult.Accepted

        assertEquals(17, refreshed?.bpBalance)
        assertEquals(17, changed.state.bpBalance)
    }

    private fun service(): TowerPlaySessionService = TowerPlaySessionService(
        registeredTeamSnapshots = TestTowerRegisteredTeamSnapshots,
    ) { entryContextId }

    private fun selectMechanic(
        service: TowerPlaySessionService,
        state: TowerPlayViewState,
    ): TowerPlayViewState = accepted(
        service.mutate(
            playerId,
            TowerPlayIntent.ChangeMechanic(UUID.randomUUID(), entryContextId, state.revision, MajorBattleMechanic.MEGA),
        ),
    )

    private fun lockEntries(service: TowerPlaySessionService, opened: TowerPlayViewState): TowerPlayViewState {
        var state = selectMechanic(service, opened)
        state.party.sortedBy(TowerPlayPartySlot::slot).take(state.format.selectionSize).forEach { slot ->
            state = accepted(service.mutate(
                playerId,
                TowerPlayIntent.ToggleSelection(UUID.randomUUID(), entryContextId, state.revision, slot.pokemonId),
            ))
        }
        return accepted(service.mutate(playerId, TowerPlayIntent.LockTeam(UUID.randomUUID(), entryContextId, state.revision), state.party))
    }

    private fun countingSnapshots(onSnapshot: () -> Unit, onDiscard: () -> Unit) = object : TowerRegisteredTeamSnapshots {
        override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult {
            onSnapshot()
            return TowerRegisteredTeamSnapshotResult.Stored
        }

        override fun discard(playerId: UUID) = onDiscard()
    }

    private fun replacementParty(): List<TowerPlayPartySlot> = validParty().mapIndexed { index, pokemon ->
        pokemon.copy(pokemonId = UUID(99, index.toLong() + 1), speciesId = "cobblemon:replacement_${index + 1}")
    }

    private fun openRequest(party: List<TowerPlayPartySlot> = validParty()) = TowerPlayOpenRequest(
        party = party,
        initialFormat = TowerBattleFormat.SINGLE,
        progressByTrack = progressByTrack(),
        bpBalance = 0,
    )

    private fun progressByTrack(): Map<TowerTrack, TowerProgress> = clearedNormalWith(mapOf(
        TowerBattleFormat.SINGLE to TowerProgress.initial(TowerBattleFormat.SINGLE),
        TowerBattleFormat.DOUBLE to TowerProgress(TowerBattleFormat.DOUBLE, 7, 9),
    ))

    private fun validParty(): List<TowerPlayPartySlot> = (1..6).map { index ->
        TowerPlayPartySlot(
            slot = index - 1,
            pokemonId = if (index == 1) pokemonId else UUID(0, index.toLong()),
            speciesId = "cobblemon:species_$index",
            heldItemId = if (index == 6) null else "minecraft:item_$index",
            level = 40 + index,
            battleLevel = minOf(40 + index, 50),
        )
    }

    private fun accepted(result: TowerPlayMutationResult): TowerPlayViewState =
        (result as TowerPlayMutationResult.Accepted).state

    private fun rejected(result: TowerPlayMutationResult): TowerPlayMutationResult.Rejected =
        result as TowerPlayMutationResult.Rejected
}
