package jbro.cobblemon.mcc.internal.tower.ui

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchRequest
import jbro.cobblemon.mcc.internal.tower.TowerBattleLauncher
import jbro.cobblemon.mcc.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.internal.tower.TowerBattleOutcome
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerProgressUpdate
import jbro.cobblemon.mcc.internal.tower.TowerProgression
import jbro.cobblemon.mcc.internal.tower.TowerTeamRegistrationIssue
import jbro.cobblemon.mcc.internal.tower.TowerTeamRegistrationResult
import jbro.cobblemon.mcc.internal.tower.TowerTeamRules
import jbro.cobblemon.mcc.internal.tower.TowerTeamSelectionIssue
import jbro.cobblemon.mcc.internal.tower.TowerTeamSelectionResult
import jbro.cobblemon.mcc.internal.tower.TowerPokemonRegistration
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.mcc.internal.tower.TowerRegisteredTeamSnapshots
import jbro.cobblemon.mcc.internal.tower.TowerSelectedTeam
import jbro.cobblemon.mcc.internal.tower.UnavailableTowerBattleLauncher
import jbro.cobblemon.mcc.internal.tower.UnavailableTowerRegisteredTeamSnapshots
import jbro.cobblemon.mcc.internal.ai.BattleTacticalRunMemoryStore
import jbro.cobblemon.mcc.internal.battle.settleBeforeTerminatingBattle

internal class TowerPlayOpenRequest(
    party: Collection<TowerPlayPartySlot>,
    val initialFormat: TowerBattleFormat,
    progressByTrack: Map<TowerTrack, TowerProgress>,
    val bpBalance: Long,
    /** The mode to open in; null opens Endless once it is unlocked and Normal before. */
    initialMode: TowerMode? = null,
) {
    val party: List<TowerPlayPartySlot> = Collections.unmodifiableList(ArrayList(party))
    val progressByTrack: Map<TowerTrack, TowerProgress> =
        Collections.unmodifiableMap(orderedProgressCopy(progressByTrack))
    val endlessUnlocked: Boolean = TowerTrack.endlessUnlocked(this.progressByTrack)
    val initialMode: TowerMode =
        if (endlessUnlocked) initialMode ?: TowerMode.ENDLESS else TowerMode.NORMAL

    init {
        require(this.progressByTrack.keys == TowerTrack.entries.toSet()) {
            "Tower play state requires progress for every battle format and mode"
        }
        require(this.progressByTrack.all { (track, progress) -> progress.track == track }) {
            "Tower progress must match its battle format and mode key"
        }
        require(bpBalance >= 0) { "BP balance cannot be negative" }
    }
}

internal object TowerPlayMessageKeys {
    private const val PREFIX = "screen.more_cobblemon_contents.tower.error"

    const val SESSION_NOT_FOUND = "$PREFIX.session_not_found"
    const val STALE_REVISION = "$PREFIX.stale_revision"
    const val REQUEST_CONFLICT = "$PREFIX.request_conflict"
    const val PHASE_INVALID = "$PREFIX.phase_invalid"
    const val POKEMON_NOT_FOUND = "$PREFIX.pokemon_not_found"
    const val PARTY_CHANGED = "$PREFIX.party_changed"
    const val SELECTION_FULL = "$PREFIX.selection_full"
    const val TEAM_INVALID = "$PREFIX.team_invalid"
    const val BATTLE_UNAVAILABLE = "$PREFIX.battle_unavailable"
    const val MECHANIC_REQUIRED = "$PREFIX.mechanic_required"
    const val NOTHING_TO_ABANDON = "$PREFIX.nothing_to_abandon"
    const val NOTHING_TO_RETIRE = "$PREFIX.nothing_to_retire"
    const val ENDLESS_LOCKED = "$PREFIX.endless_locked"

    const val PARTY_SIZE = "$PREFIX.party_size"
    const val DUPLICATE_POKEMON = "$PREFIX.duplicate_pokemon"
    const val DUPLICATE_SPECIES = "$PREFIX.duplicate_species"
    const val DUPLICATE_HELD_ITEM = "$PREFIX.duplicate_held_item"
    const val SELECTION_SIZE = "$PREFIX.selection_size"
    const val UNREGISTERED_POKEMON = "$PREFIX.unregistered_pokemon"
    const val LEGENDARY_CLASS_NOT_ALLOWED = "$PREFIX.legendary_class_not_allowed"
    const val TOO_MANY_LEGENDARY_CLASS = "$PREFIX.too_many_legendary_class"

}

internal fun interface TowerPlayBattleCompletionSink {
    fun record(playerId: UUID, update: TowerProgressUpdate)
}

private val NoopTowerPlayBattleCompletionSink = TowerPlayBattleCompletionSink { _, _ -> }

/** Ends the stored run of [track] for a challenger who gives it up between battles: the streak goes to 0. */
internal fun interface TowerPlayRunRetirementSink {
    fun retire(playerId: UUID, track: TowerTrack)
}

private val NoopTowerPlayRunRetirementSink = TowerPlayRunRetirementSink { _, _ -> }

internal sealed interface TowerPlayBattleCompletionResult {
    data class Completed(val state: TowerPlayViewState) : TowerPlayBattleCompletionResult
    data object SessionAbandoned : TowerPlayBattleCompletionResult
    data object SessionNotFound : TowerPlayBattleCompletionResult
    data object NoActiveBattle : TowerPlayBattleCompletionResult
    data class StaleBattle(val activeBattleId: UUID) : TowerPlayBattleCompletionResult
}

internal sealed interface TowerSessionAbandonResult {
    data object NoSession : TowerSessionAbandonResult
    data object SessionClosed : TowerSessionAbandonResult
    data class ForfeitRequested(val battleId: UUID) : TowerSessionAbandonResult
    data class ForfeitUnavailable(val battleId: UUID) : TowerSessionAbandonResult
}

internal class TowerPlaySessionService(
    private val battleLauncher: TowerBattleLauncher = UnavailableTowerBattleLauncher,
    private val registeredTeamSnapshots: TowerRegisteredTeamSnapshots = UnavailableTowerRegisteredTeamSnapshots,
    private val battleCompletionSink: TowerPlayBattleCompletionSink = NoopTowerPlayBattleCompletionSink,
    private val runRetirementSink: TowerPlayRunRetirementSink = NoopTowerPlayRunRetirementSink,
    private val entryContextIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val sessions = HashMap<UUID, Session>()
    private val launchingPlayers = HashSet<UUID>()

    @Synchronized
    fun open(
        playerId: UUID,
        request: TowerPlayOpenRequest,
        entryContext: TowerPlayEntryContext = TowerPlayEntryContext.Command(entryContextIdFactory()),
    ): TowerPlayViewState {
        sessions[playerId]?.let { existing ->
            if (existing.state.phase != TowerPlayPhase.SELECTING) return existing.state
            val keepRegisteredTeam = existing.hasRegisteredSnapshot
            val progressByTrack = if (keepRegisteredTeam) {
                existing.progressByTrack
            } else {
                request.progressByTrack
            }
            val progress = progressByTrack.getValue(TowerTrack(existing.state.format, existing.state.mode))
            val refreshed = viewState(
                entryContextId = entryContext.entryContextId,
                revision = 0,
                phase = TowerPlayPhase.SELECTING,
                party = if (keepRegisteredTeam) existing.state.party else request.party,
                selected = emptySet(),
                progress = progress,
                bpBalance = request.bpBalance,
                errorKeys = if (keepRegisteredTeam) existing.state.errorKeys else registrationErrors(request.party),
                selectedMechanic = existing.state.selectedMechanic ?: DEFAULT_TOWER_MECHANIC,
                mechanicLocked = existing.state.mechanicLocked,
                legendaryClassAllowed = existing.state.legendaryClassAllowed,
                legendaryClassLocked = existing.state.legendaryClassLocked,
                endlessUnlocked = TowerTrack.endlessUnlocked(progressByTrack),
            )
            sessions[playerId] = Session(
                progressByTrack = progressByTrack,
                entryContext = entryContext,
                state = refreshed,
                lockedSelection = existing.lockedSelection,
                hasRegisteredSnapshot = keepRegisteredTeam,
            )
            return refreshed
        }
        registeredTeamSnapshots.discard(playerId)
        val progress = request.progressByTrack.getValue(TowerTrack(request.initialFormat, request.initialMode))
        val state = viewState(
            entryContextId = entryContext.entryContextId,
            revision = 0,
            phase = TowerPlayPhase.SELECTING,
            party = request.party,
            selected = emptySet(),
            progress = progress,
            bpBalance = request.bpBalance,
            errorKeys = registrationErrors(request.party),
            selectedMechanic = DEFAULT_TOWER_MECHANIC,
            endlessUnlocked = request.endlessUnlocked,
        )
        sessions[playerId] = Session(request.progressByTrack, entryContext, state)
        return state
    }

    @Synchronized
    fun current(playerId: UUID): TowerPlayViewState? = sessions[playerId]?.state

    @Synchronized
    fun entryContext(playerId: UUID): TowerPlayEntryContext? = sessions[playerId]?.entryContext

    @Synchronized
    fun progress(playerId: UUID): Map<TowerTrack, TowerProgress>? =
        sessions[playerId]?.progressByTrack?.toMap()

    @Synchronized
    fun activeBattleId(playerId: UUID): UUID? = sessions[playerId]?.activeBattleId

    @Synchronized
    fun adminSetProgress(playerId: UUID, progress: TowerProgress): Boolean {
        val session = sessions[playerId] ?: return true
        val current = session.state.format == progress.format && session.state.mode == progress.mode
        if (session.activeBattleId != null && current) return false
        session.progressByTrack[progress.track] = progress
        session.state = session.state.copy(
            revision = session.state.revision + 1,
            currentWinStreak = if (current) progress.currentWinStreak else session.state.currentWinStreak,
            bestWinStreak = if (current) progress.bestWinStreak else session.state.bestWinStreak,
            endlessUnlocked = TowerTrack.endlessUnlocked(session.progressByTrack) || session.state.mode == TowerMode.ENDLESS,
        )
        return true
    }

    @Synchronized
    fun refreshBpBalance(playerId: UUID, balance: Long): TowerPlayViewState? {
        require(balance >= 0) { "BP balance cannot be negative" }
        val session = sessions[playerId] ?: return null
        return session.state.copy(bpBalance = balance).also { session.state = it }
    }

    /**
     * Shows [party] in a selecting session that holds no registered team, as after a run ended; a session still
     * on its registered team keeps it.
     */
    @Synchronized
    fun refreshParty(playerId: UUID, party: Collection<TowerPlayPartySlot>): TowerPlayViewState? {
        val session = sessions[playerId] ?: return null
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING || session.hasRegisteredSnapshot) return state
        if (party.sortedBy(TowerPlayPartySlot::slot) == state.party.sortedBy(TowerPlayPartySlot::slot)) return state
        return state.copy(
            revision = state.revision + 1,
            party = party,
            selectedPokemonIds = emptySet(),
            errorKeys = registrationErrors(party),
        ).also { session.state = it }
    }

    @Synchronized
    fun completeBattle(
        playerId: UUID,
        battleId: UUID,
        outcome: TowerBattleOutcome,
        completionSink: TowerPlayBattleCompletionSink = battleCompletionSink,
    ): TowerPlayBattleCompletionResult = finishBattle(playerId, battleId, outcome, completionSink)

    @Synchronized
    fun abandonSession(
        playerId: UUID,
        forfeit: (UUID) -> Boolean,
    ): TowerSessionAbandonResult {
        val session = sessions[playerId] ?: return TowerSessionAbandonResult.NoSession
        val battleId = session.activeBattleId
        if (battleId == null) {
            removeSessionAndDiscardSnapshot(playerId)
            return TowerSessionAbandonResult.SessionClosed
        }
        if (session.abandonRequested) return TowerSessionAbandonResult.ForfeitRequested(battleId)

        session.abandonRequested = true
        val requested = try {
            forfeit(battleId)
        } catch (exception: RuntimeException) {
            if (sessions[playerId] !== session) return TowerSessionAbandonResult.SessionClosed
            session.abandonRequested = false
            throw exception
        } catch (exception: LinkageError) {
            if (sessions[playerId] !== session) return TowerSessionAbandonResult.SessionClosed
            session.abandonRequested = false
            throw exception
        }
        if (sessions[playerId] !== session) return TowerSessionAbandonResult.SessionClosed
        if (!requested) {
            session.abandonRequested = false
            return TowerSessionAbandonResult.ForfeitUnavailable(battleId)
        }
        return TowerSessionAbandonResult.ForfeitRequested(battleId)
    }

    private fun finishBattle(
        playerId: UUID,
        battleId: UUID,
        outcome: TowerBattleOutcome,
        completionSink: TowerPlayBattleCompletionSink,
    ): TowerPlayBattleCompletionResult {
        val session = sessions[playerId] ?: return TowerPlayBattleCompletionResult.SessionNotFound
        val activeBattleId = session.activeBattleId ?: return TowerPlayBattleCompletionResult.NoActiveBattle
        if (activeBattleId != battleId) return TowerPlayBattleCompletionResult.StaleBattle(activeBattleId)

        val state = session.state
        val track = TowerTrack(state.format, state.mode)
        val progress = session.progressByTrack.getValue(track)
        val recordedOutcome = if (session.abandonRequested) TowerBattleOutcome.LOSS else outcome
        val update = TowerProgression.record(progress, recordedOutcome)
        completionSink.record(playerId, update)

        // A Normal clear ends the run: the next one starts from the first battle, and Endless is open.
        val after = if (update.cleared) update.after.copy(currentWinStreak = 0) else update.after
        session.progressByTrack[track] = after
        session.activeBattleId = null
        if (session.abandonRequested) {
            removeSessionAndDiscardSnapshot(playerId)
            return TowerPlayBattleCompletionResult.SessionAbandoned
        }
        // A loss or a Normal clear ends the run: the next one picks its team and rules again.
        if (update.cleared || recordedOutcome == TowerBattleOutcome.LOSS) {
            return TowerPlayBattleCompletionResult.Completed(endRun(session, party = null))
        }
        val updated = state.copy(
            revision = state.revision + 1,
            phase = TowerPlayPhase.TEAM_LOCKED,
            currentWinStreak = update.after.currentWinStreak,
            bestWinStreak = update.after.bestWinStreak,
        )
        session.state = updated
        return TowerPlayBattleCompletionResult.Completed(updated)
    }

    @Synchronized
    fun cancelBattle(
        playerId: UUID,
        battleId: UUID,
        completionSink: TowerPlayBattleCompletionSink = battleCompletionSink,
    ): TowerPlayBattleCompletionResult {
        val session = sessions[playerId] ?: return TowerPlayBattleCompletionResult.SessionNotFound
        val activeBattleId = session.activeBattleId ?: return TowerPlayBattleCompletionResult.NoActiveBattle
        if (activeBattleId != battleId) return TowerPlayBattleCompletionResult.StaleBattle(activeBattleId)
        if (session.abandonRequested) {
            return finishBattle(playerId, battleId, TowerBattleOutcome.LOSS, completionSink)
        }

        session.activeBattleId = null
        val updated = session.state.copy(
            revision = session.state.revision + 1,
            phase = TowerPlayPhase.TEAM_LOCKED,
        )
        session.state = updated
        return TowerPlayBattleCompletionResult.Completed(updated)
    }

    @Synchronized
    fun close(playerId: UUID): Boolean = removeSessionAndDiscardSnapshot(playerId) != null

    @Synchronized
    fun activeBattleIds(): Set<UUID> = sessions.values.mapNotNullTo(LinkedHashSet()) { it.activeBattleId }

    @Synchronized
    fun isLaunchPending(playerId: UUID): Boolean = playerId in launchingPlayers

    @Synchronized
    fun count(): Int = sessions.size

    @Synchronized
    fun clear() {
        var failure: Throwable? = null
        sessions.keys.toList().forEach { playerId ->
            try {
                removeSessionAndDiscardSnapshot(playerId)
            } catch (cleanupFailure: Throwable) {
                if (failure == null) failure = cleanupFailure else if (failure !== cleanupFailure) {
                    failure?.addSuppressed(cleanupFailure)
                }
            }
        }
        launchingPlayers.clear()
        failure?.let { throw it }
    }

    @Synchronized
    fun disconnect(
        playerId: UUID,
        completionSink: TowerPlayBattleCompletionSink = battleCompletionSink,
        terminateBattle: (UUID) -> Unit = {},
    ): Boolean {
        val session = sessions[playerId] ?: return false
        try {
            session.activeBattleId?.let { battleId ->
                settleBeforeTerminatingBattle(
                    battleId,
                    settle = { finishBattle(playerId, it, TowerBattleOutcome.LOSS, completionSink) },
                    terminate = terminateBattle,
                )
            }
        } catch (failure: Throwable) {
            removeSessionAndDiscardSnapshot(playerId, failure)
            throw failure
        }
        removeSessionAndDiscardSnapshot(playerId)
        return true
    }

    @Synchronized
    fun mutate(
        playerId: UUID,
        intent: TowerPlayIntent,
        currentParty: Collection<TowerPlayPartySlot>? = null,
    ): TowerPlayMutationResult {
        val session = sessions[playerId]
            ?: return rejected(intent, 0, TowerPlayMessageKeys.SESSION_NOT_FOUND)
        if (intent.entryContextId != session.state.entryContextId) {
            return rejected(intent, session.state.revision, TowerPlayMessageKeys.SESSION_NOT_FOUND)
        }

        session.responses[intent.requestId]?.let { cached ->
            return if (cached.intent == intent) {
                cached.result
            } else {
                rejected(intent, session.state.revision, TowerPlayMessageKeys.REQUEST_CONFLICT)
            }
        }
        if (intent.expectedRevision != session.state.revision) {
            return cache(
                session,
                intent,
                rejected(intent, session.state.revision, TowerPlayMessageKeys.STALE_REVISION),
            )
        }

        val result = when (intent) {
            is TowerPlayIntent.ToggleSelection -> toggle(session, intent)
            is TowerPlayIntent.ChangeFormat -> changeFormat(session, intent)
            is TowerPlayIntent.ChangeMode -> changeMode(session, intent)
            is TowerPlayIntent.ChangeMechanic -> changeMechanic(session, intent)
            is TowerPlayIntent.ChangeLegendaryClassAllowed -> changeLegendaryClassAllowed(session, intent)
            is TowerPlayIntent.LockTeam -> lockTeam(playerId, session, intent, currentParty)
            is TowerPlayIntent.Start -> startBattle(playerId, session, intent)
            is TowerPlayIntent.Resume -> rejected(intent, session.state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            is TowerPlayIntent.Abandon -> abandon(playerId, session, intent)
            is TowerPlayIntent.Retire -> retire(playerId, session, intent, currentParty)
        }
        return cache(session, intent, result)
    }

    private fun toggle(
        session: Session,
        intent: TowerPlayIntent.ToggleSelection,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        if (state.party.none { it.pokemonId == intent.pokemonId }) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.POKEMON_NOT_FOUND)
        }

        val selected = LinkedHashSet(state.selectedPokemonOrder)
        if (!selected.remove(intent.pokemonId)) {
            if (selected.size >= state.format.selectionSize) {
                return rejected(intent, state.revision, TowerPlayMessageKeys.SELECTION_FULL)
            }
            selected.add(intent.pokemonId)
        }
        return accept(session, intent, state.copy(revision = state.revision + 1, selectedPokemonIds = selected))
    }

    private fun changeFormat(
        session: Session,
        intent: TowerPlayIntent.ChangeFormat,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        return switchTrack(session, intent, TowerTrack(intent.format, state.mode))
    }

    private fun changeMode(
        session: Session,
        intent: TowerPlayIntent.ChangeMode,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        if (intent.mode == TowerMode.ENDLESS && !state.endlessUnlocked) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.ENDLESS_LOCKED)
        }
        return switchTrack(session, intent, TowerTrack(state.format, intent.mode))
    }

    /** Moves a selecting session to [track]'s run: its own streak, and a fresh pick of the team. */
    private fun switchTrack(
        session: Session,
        intent: TowerPlayIntent,
        track: TowerTrack,
    ): TowerPlayMutationResult {
        val state = session.state
        val progress = session.progressByTrack.getValue(track)
        session.lockedSelection = null
        return accept(
            session,
            intent,
            viewState(
                entryContextId = state.entryContextId,
                revision = state.revision + 1,
                phase = TowerPlayPhase.SELECTING,
                party = state.party,
                selected = emptySet(),
                progress = progress,
                bpBalance = state.bpBalance,
                errorKeys = state.errorKeys,
                selectedMechanic = state.selectedMechanic,
                mechanicLocked = state.mechanicLocked,
                legendaryClassAllowed = state.legendaryClassAllowed,
                legendaryClassLocked = state.legendaryClassLocked,
                endlessUnlocked = state.endlessUnlocked,
            ),
        )
    }

    private fun changeMechanic(
        session: Session,
        intent: TowerPlayIntent.ChangeMechanic,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING || state.mechanicLocked) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        return accept(
            session,
            intent,
            state.copy(revision = state.revision + 1, selectedMechanic = intent.mechanic),
        )
    }

    private fun changeLegendaryClassAllowed(
        session: Session,
        intent: TowerPlayIntent.ChangeLegendaryClassAllowed,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING || state.legendaryClassLocked) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        return accept(
            session,
            intent,
            state.copy(revision = state.revision + 1, legendaryClassAllowed = intent.allowed),
        )
    }

    private fun lockTeam(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.LockTeam,
        currentParty: Collection<TowerPlayPartySlot>?,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        if (state.selectedMechanic == null) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.MECHANIC_REQUIRED)
        }
        if (!session.hasRegisteredSnapshot && currentParty != null &&
            currentParty.sortedBy { it.slot } != state.party.sortedBy { it.slot }
        ) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PARTY_CHANGED)
        }
        val registrations = state.party.map(TowerPlayPartySlot::asRegistration)
        val registration = TowerTeamRules.register(registrations)
        if (registration is TowerTeamRegistrationResult.Rejected) {
            return rejected(
                intent,
                state.revision,
                TowerPlayMessageKeys.TEAM_INVALID,
                registration.issues.associateIssueKeys(),
            )
        }
        registration as TowerTeamRegistrationResult.Accepted
        val selection = TowerTeamRules.select(
            registration.team,
            state.format,
            state.selectedPokemonOrder,
            state.legendaryClassAllowed,
        )
        if (selection is TowerTeamSelectionResult.Rejected) {
            return rejected(
                intent,
                state.revision,
                TowerPlayMessageKeys.TEAM_INVALID,
                selection.issues.associateSelectionIssueKeys(),
            )
        }
        selection as TowerTeamSelectionResult.Accepted
        if (!session.hasRegisteredSnapshot) {
            if (registeredTeamSnapshots.snapshot(playerId, registration.team) != TowerRegisteredTeamSnapshotResult.Stored) {
                return rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            }
            session.hasRegisteredSnapshot = true
        }
        session.lockedSelection = selection.selection
        return accept(
            session,
            intent,
            state.copy(revision = state.revision + 1, phase = TowerPlayPhase.TEAM_LOCKED),
        )
    }

    private fun startBattle(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.Start,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase != TowerPlayPhase.TEAM_LOCKED) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        val selection = checkNotNull(session.lockedSelection) {
            "A locked Battle Tower session must retain its validated selection"
        }
        val progress = session.progressByTrack.getValue(TowerTrack(state.format, state.mode))
        val mechanic = checkNotNull(state.selectedMechanic) {
            "A locked Battle Tower team must retain its selected mechanic"
        }
        check(launchingPlayers.add(playerId)) { "Battle Tower session is already launching a battle" }
        return try {
            when (
                val launch = battleLauncher.launch(
                    TowerBattleLaunchRequest(
                        playerId = playerId,
                        progress = progress,
                        selection = selection,
                        playerTeamPreview = state.publicTeamPreview(),
                        mechanic = mechanic,
                        legendaryClassAllowed = state.legendaryClassAllowed,
                        learningScopeId = session.state.entryContextId,
                    ),
                )
            ) {
                is TowerBattleLaunchResult.Started -> {
                    session.activeBattleId = launch.battleId
                    accept(
                        session,
                        intent,
                        state.copy(
                            revision = state.revision + 1,
                            phase = TowerPlayPhase.ACTIVE,
                            mechanicLocked = true,
                            legendaryClassLocked = true,
                        ),
                    )
                }

                TowerBattleLaunchResult.Unavailable ->
                    rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            }
        } finally {
            launchingPlayers.remove(playerId)
        }
    }

    private fun abandon(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.Abandon,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase == TowerPlayPhase.SELECTING) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.NOTHING_TO_ABANDON)
        }
        if (state.phase == TowerPlayPhase.ACTIVE) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
        }
        session.lockedSelection = null
        return accept(
            session,
            intent,
            state.copy(
                revision = state.revision + 1,
                phase = TowerPlayPhase.SELECTING,
                selectedPokemonIds = emptySet(),
            ),
        )
    }

    /** Gives up the run between battles: the streak ends as a loss would end it, without a battle. */
    private fun retire(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.Retire,
        currentParty: Collection<TowerPlayPartySlot>?,
    ): TowerPlayMutationResult {
        val state = session.state
        if (state.phase == TowerPlayPhase.ACTIVE) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
        }
        if (!TowerPlayInteractionPolicy.runInProgress(state)) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.NOTHING_TO_RETIRE)
        }
        val track = TowerTrack(state.format, state.mode)
        runRetirementSink.retire(playerId, track)
        session.progressByTrack[track] = session.progressByTrack.getValue(track).copy(currentWinStreak = 0)
        return accept(session, intent, endRun(session, currentParty))
    }

    /**
     * Ends the session's run on its stored progress: back to selecting, with the registered team released (the next
     * lock registers [party], or the shown party when null) and the rules open again.
     */
    private fun endRun(session: Session, party: Collection<TowerPlayPartySlot>?): TowerPlayViewState {
        session.lockedSelection = null
        // The next lock takes a new snapshot, which replaces this run's.
        session.hasRegisteredSnapshot = false
        val state = session.state
        val progress = session.progressByTrack.getValue(TowerTrack(state.format, state.mode))
        return state.copy(
            revision = state.revision + 1,
            phase = TowerPlayPhase.SELECTING,
            party = party ?: state.party,
            selectedPokemonIds = emptySet(),
            currentWinStreak = progress.currentWinStreak,
            bestWinStreak = progress.bestWinStreak,
            errorKeys = party?.let(::registrationErrors) ?: state.errorKeys,
            mechanicLocked = false,
            legendaryClassLocked = false,
            endlessUnlocked = state.endlessUnlocked || TowerTrack.endlessUnlocked(session.progressByTrack),
        ).also { session.state = it }
    }

    private fun accept(
        session: Session,
        intent: TowerPlayIntent,
        state: TowerPlayViewState,
    ): TowerPlayMutationResult.Accepted {
        session.state = state
        return TowerPlayMutationResult.Accepted(intent.requestId, state)
    }

    private fun removeSession(playerId: UUID): Session? = sessions.remove(playerId)?.also {
        BattleTacticalRunMemoryStore.discard(it.state.entryContextId)
    }

    private fun removeSessionAndDiscardSnapshot(
        playerId: UUID,
        primaryFailure: Throwable? = null,
    ): Session? {
        var removed: Session? = null
        var failure = primaryFailure
        try {
            removed = removeSession(playerId)
        } catch (cleanupFailure: Throwable) {
            if (failure == null) failure = cleanupFailure else if (failure !== cleanupFailure) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        try {
            registeredTeamSnapshots.discard(playerId)
        } catch (cleanupFailure: Throwable) {
            if (failure == null) failure = cleanupFailure else if (failure !== cleanupFailure) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        if (primaryFailure == null) failure?.let { throw it }
        return removed
    }

    private fun rejected(
        intent: TowerPlayIntent,
        revision: Long,
        messageKey: String,
        fieldErrors: Map<String, String> = emptyMap(),
    ) = TowerPlayMutationResult.Rejected(intent.requestId, revision, messageKey, fieldErrors)

    private fun cache(
        session: Session,
        intent: TowerPlayIntent,
        result: TowerPlayMutationResult,
    ): TowerPlayMutationResult {
        if (session.responses.size == MAX_CACHED_RESPONSES) {
            session.responses.remove(session.responses.keys.first())
        }
        session.responses[intent.requestId] = CachedResponse(intent, result)
        return result
    }

    private class Session(
        progressByTrack: Map<TowerTrack, TowerProgress>,
        val entryContext: TowerPlayEntryContext,
        var state: TowerPlayViewState,
        var lockedSelection: TowerSelectedTeam? = null,
        var activeBattleId: UUID? = null,
        var hasRegisteredSnapshot: Boolean = false,
        var abandonRequested: Boolean = false,
        val responses: LinkedHashMap<UUID, CachedResponse> = LinkedHashMap(),
    ) {
        val progressByTrack: MutableMap<TowerTrack, TowerProgress> = progressByTrack.toMutableMap()
    }

    private data class CachedResponse(
        val intent: TowerPlayIntent,
        val result: TowerPlayMutationResult,
    )
}

private fun viewState(
    entryContextId: UUID,
    revision: Long,
    phase: TowerPlayPhase,
    party: Collection<TowerPlayPartySlot>,
    selected: Collection<UUID>,
    progress: TowerProgress,
    bpBalance: Long,
    errorKeys: Collection<String>,
    selectedMechanic: MajorBattleMechanic? = null,
    mechanicLocked: Boolean = false,
    legendaryClassAllowed: Boolean = false,
    legendaryClassLocked: Boolean = false,
    endlessUnlocked: Boolean = false,
): TowerPlayViewState = TowerPlayViewState(
    entryContextId = entryContextId,
    revision = revision,
    format = progress.format,
    mode = progress.mode,
    endlessUnlocked = endlessUnlocked || progress.mode == TowerMode.ENDLESS,
    phase = phase,
    party = party,
    selectedPokemonIds = selected,
    currentWinStreak = progress.currentWinStreak,
    bestWinStreak = progress.bestWinStreak,
    bpBalance = bpBalance,
    errorKeys = errorKeys,
    selectedMechanic = selectedMechanic,
    mechanicLocked = mechanicLocked,
    legendaryClassAllowed = legendaryClassAllowed,
    legendaryClassLocked = legendaryClassLocked,
)

private val DEFAULT_TOWER_MECHANIC = MajorBattleMechanic.DYNAMAX

private fun registrationErrors(party: Collection<TowerPlayPartySlot>): List<String> =
    when (val result = TowerTeamRules.register(party.map(TowerPlayPartySlot::asRegistration))) {
        is TowerTeamRegistrationResult.Accepted -> emptyList()
        is TowerTeamRegistrationResult.Rejected -> result.issues.map(TowerTeamRegistrationIssue::messageKey).distinct()
    }

private fun TowerPlayPartySlot.asRegistration() = TowerPokemonRegistration(
    pokemonId,
    speciesId,
    heldItemId,
    level,
    legendaryClass,
    formId,
)

private fun TowerPlayViewState.publicTeamPreview() = BattleOpponentTeamPreviewView(
    selectionSize = format.selectionSize,
    pokemon = party.sortedBy(TowerPlayPartySlot::slot).map { pokemon ->
        BattleOpponentTeamPreviewPokemonView(
            previewSlotId = pokemon.slot,
            speciesId = pokemon.speciesId,
            formId = pokemon.formId,
            level = pokemon.battleLevel,
        )
    },
)

private fun List<TowerTeamRegistrationIssue>.associateIssueKeys(): Map<String, String> =
    associate { issue ->
        when (issue) {
            is TowerTeamRegistrationIssue.WrongTeamSize -> "party"
            is TowerTeamRegistrationIssue.DuplicatePokemon -> "pokemon"
            is TowerTeamRegistrationIssue.DuplicateSpecies -> "species"
            is TowerTeamRegistrationIssue.DuplicateHeldItem -> "held_item"
        } to issue.messageKey()
    }

private fun List<TowerTeamSelectionIssue>.associateSelectionIssueKeys(): Map<String, String> =
    associate { issue ->
        when (issue) {
            is TowerTeamSelectionIssue.WrongSelectionSize -> "selection"
            is TowerTeamSelectionIssue.DuplicatePokemon -> "pokemon"
            is TowerTeamSelectionIssue.UnregisteredPokemon -> "pokemon"
            is TowerTeamSelectionIssue.LegendaryClassNotAllowed -> "pokemon"
            is TowerTeamSelectionIssue.TooManyLegendaryClass -> "selection"
        } to issue.messageKey()
    }

private fun TowerTeamRegistrationIssue.messageKey(): String = when (this) {
    is TowerTeamRegistrationIssue.WrongTeamSize -> TowerPlayMessageKeys.PARTY_SIZE
    is TowerTeamRegistrationIssue.DuplicatePokemon -> TowerPlayMessageKeys.DUPLICATE_POKEMON
    is TowerTeamRegistrationIssue.DuplicateSpecies -> TowerPlayMessageKeys.DUPLICATE_SPECIES
    is TowerTeamRegistrationIssue.DuplicateHeldItem -> TowerPlayMessageKeys.DUPLICATE_HELD_ITEM
}

private fun TowerTeamSelectionIssue.messageKey(): String = when (this) {
    is TowerTeamSelectionIssue.WrongSelectionSize -> TowerPlayMessageKeys.SELECTION_SIZE
    is TowerTeamSelectionIssue.DuplicatePokemon -> TowerPlayMessageKeys.DUPLICATE_POKEMON
    is TowerTeamSelectionIssue.UnregisteredPokemon -> TowerPlayMessageKeys.UNREGISTERED_POKEMON
    is TowerTeamSelectionIssue.LegendaryClassNotAllowed -> TowerPlayMessageKeys.LEGENDARY_CLASS_NOT_ALLOWED
    is TowerTeamSelectionIssue.TooManyLegendaryClass -> TowerPlayMessageKeys.TOO_MANY_LEGENDARY_CLASS
}

private fun orderedProgressCopy(source: Map<TowerTrack, TowerProgress>): LinkedHashMap<TowerTrack, TowerProgress> =
    LinkedHashMap<TowerTrack, TowerProgress>().apply {
        TowerTrack.entries.forEach { track -> source[track]?.let { put(track, it) } }
    }

private const val MAX_CACHED_RESPONSES = 64
