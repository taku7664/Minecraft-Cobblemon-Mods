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
    const val NOTHING_TO_CHANGE = "$PREFIX.nothing_to_change"
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

/**
 * Ends the stored run of [track] without a battle: the streak goes to 0. A challenger gives a run up between battles,
 * and a session that closes takes its runs with it, since their registered team goes with the session.
 */
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
    /** Asks the battle under way to forfeit; true once the request went through. */
    private val battleForfeit: (playerId: UUID, battleId: UUID) -> Boolean = { _, _ -> false },
    private val runScopeIdFactory: () -> UUID = UUID::randomUUID,
    private val entryContextIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val sessions = HashMap<UUID, Session>()
    private val launchingPlayers = HashSet<UUID>()

    /**
     * Opens the Tower for [playerId]. A session holding a registered team shows its run as it stands; a selecting one
     * takes the party, progress and balance [request] read now and keeps the rules the challenger chose.
     */
    @Synchronized
    fun open(
        playerId: UUID,
        request: TowerPlayOpenRequest,
        entryContext: TowerPlayEntryContext = TowerPlayEntryContext.Command(entryContextIdFactory()),
    ): TowerPlayViewState {
        sessions[playerId]?.let { existing ->
            val shown = existing.state
            if (shown.phase != TowerPlayPhase.SELECTING) return shown
            val progress = request.progressByTrack.getValue(TowerTrack(shown.format, shown.mode))
            val refreshed = viewState(
                entryContextId = entryContext.entryContextId,
                revision = 0,
                phase = TowerPlayPhase.SELECTING,
                party = request.party,
                selected = shown.selectedPokemonOrder.keptIn(request.party),
                progress = progress,
                bpBalance = request.bpBalance,
                errorKeys = registrationErrors(request.party),
                selectedMechanic = shown.selectedMechanic ?: DEFAULT_TOWER_MECHANIC,
                legendaryClassAllowed = shown.legendaryClassAllowed,
                endlessUnlocked = request.endlessUnlocked,
            )
            sessions[playerId] = Session(request.progressByTrack, entryContext, refreshed)
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

    /** Shows [party] in a selecting session; a registered team stays as it is until its run ends. */
    @Synchronized
    fun refreshParty(playerId: UUID, party: Collection<TowerPlayPartySlot>): TowerPlayViewState? {
        val session = sessions[playerId] ?: return null
        val state = session.state
        if (state.phase != TowerPlayPhase.SELECTING) return state
        if (party.sortedBy(TowerPlayPartySlot::slot) == state.party.sortedBy(TowerPlayPartySlot::slot)) return state
        return state.copy(
            revision = state.revision + 1,
            party = party,
            selectedPokemonIds = state.selectedPokemonOrder.keptIn(party),
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

    /** Closes [playerId]'s session; a battle under way is forfeited first and the session closes once it settles. */
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
        if (session.forfeitRequested) {
            session.closeAfterBattle = true
            return TowerSessionAbandonResult.ForfeitRequested(battleId)
        }
        return when (requestForfeit(playerId, session, battleId, forfeit, close = true)) {
            ForfeitRequest.REQUESTED -> TowerSessionAbandonResult.ForfeitRequested(battleId)
            ForfeitRequest.UNAVAILABLE -> TowerSessionAbandonResult.ForfeitUnavailable(battleId)
            ForfeitRequest.SETTLED -> TowerSessionAbandonResult.SessionClosed
        }
    }

    /**
     * Asks [battleId] to forfeit, with [session] marked first: a battle that ends inside the call already counts as
     * the loss. [close] also closes the session once the battle settles.
     */
    private fun requestForfeit(
        playerId: UUID,
        session: Session,
        battleId: UUID,
        forfeit: (UUID) -> Boolean,
        close: Boolean,
    ): ForfeitRequest {
        val closeBefore = session.closeAfterBattle
        session.forfeitRequested = true
        session.closeAfterBattle = closeBefore || close
        fun settled() = sessions[playerId] !== session || session.activeBattleId != battleId
        fun withdraw() {
            session.forfeitRequested = false
            session.closeAfterBattle = closeBefore
        }
        val requested = try {
            forfeit(battleId)
        } catch (failure: RuntimeException) {
            if (settled()) return ForfeitRequest.SETTLED
            withdraw()
            throw failure
        } catch (failure: LinkageError) {
            if (settled()) return ForfeitRequest.SETTLED
            withdraw()
            throw failure
        }
        if (settled()) return ForfeitRequest.SETTLED
        if (!requested) {
            withdraw()
            return ForfeitRequest.UNAVAILABLE
        }
        return ForfeitRequest.REQUESTED
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
        val recordedOutcome = if (session.forfeitRequested) TowerBattleOutcome.LOSS else outcome
        val update = TowerProgression.record(progress, recordedOutcome)
        completionSink.record(playerId, update)

        // A Normal clear ends the run: the next one starts from the first battle, and Endless is open.
        val after = if (update.cleared) update.after.copy(currentWinStreak = 0) else update.after
        session.progressByTrack[track] = after
        session.activeBattleId = null
        session.forfeitRequested = false
        if (session.closeAfterBattle) {
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
        // A battle given up stays a loss even when it ends without a result.
        if (session.forfeitRequested || session.closeAfterBattle) {
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

    /**
     * Applies [intent]. [currentParty] is the challenger's party now, read for the intents that register it or show
     * it once a registration is let go: a lock, a team change and giving up.
     */
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
            is TowerPlayIntent.ChangeFormat -> changeRules(session, intent) { switchTrack(session, TowerTrack(intent.format, it.mode)) }
            is TowerPlayIntent.ChangeMode -> changeMode(session, intent)
            is TowerPlayIntent.ChangeMechanic -> changeRules(session, intent) { it.copy(selectedMechanic = intent.mechanic) }
            is TowerPlayIntent.ChangeLegendaryClassAllowed ->
                changeRules(session, intent) { it.copy(legendaryClassAllowed = intent.allowed) }
            is TowerPlayIntent.LockTeam -> lockTeam(playerId, session, intent, currentParty)
            is TowerPlayIntent.Start -> startBattle(playerId, session, intent)
            is TowerPlayIntent.ChangeTeam -> changeTeam(session, intent, currentParty)
            is TowerPlayIntent.Forfeit -> forfeit(playerId, session, intent)
            is TowerPlayIntent.Retire -> retire(playerId, session, intent, currentParty)
        }
        return cache(session, intent, result)
    }

    private fun toggle(
        session: Session,
        intent: TowerPlayIntent.ToggleSelection,
    ): TowerPlayMutationResult {
        val state = session.state
        if (!TowerPlayInteractionPolicy.picking(state)) {
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

    /** Changes a session rule, open only while no team is registered. */
    private fun changeRules(
        session: Session,
        intent: TowerPlayIntent,
        change: (TowerPlayViewState) -> TowerPlayViewState,
    ): TowerPlayMutationResult {
        val state = session.state
        if (!TowerPlayInteractionPolicy.rulesOpen(state)) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        return accept(session, intent, change(state).copy(revision = state.revision + 1))
    }

    private fun changeMode(
        session: Session,
        intent: TowerPlayIntent.ChangeMode,
    ): TowerPlayMutationResult {
        val state = session.state
        if (TowerPlayInteractionPolicy.rulesOpen(state) && intent.mode == TowerMode.ENDLESS && !state.endlessUnlocked) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.ENDLESS_LOCKED)
        }
        return changeRules(session, intent) { switchTrack(session, TowerTrack(it.format, intent.mode)) }
    }

    /** [track]'s run as a selecting session shows it: its own streak, and a fresh pick of the entries. */
    private fun switchTrack(session: Session, track: TowerTrack): TowerPlayViewState {
        val state = session.state
        return viewState(
            entryContextId = state.entryContextId,
            revision = state.revision,
            phase = TowerPlayPhase.SELECTING,
            party = state.party,
            selected = emptySet(),
            progress = session.progressByTrack.getValue(track),
            bpBalance = state.bpBalance,
            errorKeys = state.errorKeys,
            selectedMechanic = state.selectedMechanic,
            legendaryClassAllowed = state.legendaryClassAllowed,
            endlessUnlocked = state.endlessUnlocked,
        )
    }

    /**
     * Fixes the entries for the next battle. From [TowerPlayPhase.SELECTING] it first registers the six shown, which
     * must still be the challenger's party; between battles it picks again from the six already registered.
     */
    private fun lockTeam(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.LockTeam,
        currentParty: Collection<TowerPlayPartySlot>?,
    ): TowerPlayMutationResult {
        val state = session.state
        if (!TowerPlayInteractionPolicy.picking(state)) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        if (state.selectedMechanic == null) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.MECHANIC_REQUIRED)
        }
        val registering = state.phase == TowerPlayPhase.SELECTING
        if (registering && currentParty != null &&
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
        if (registering &&
            registeredTeamSnapshots.snapshot(playerId, registration.team) != TowerRegisteredTeamSnapshotResult.Stored
        ) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
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
        val runScopeId = session.runScopeId ?: runScopeIdFactory()
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
                        learningScopeId = runScopeId,
                    ),
                )
            ) {
                is TowerBattleLaunchResult.Started -> {
                    session.activeBattleId = launch.battleId
                    session.runScopeId = runScopeId
                    accept(
                        session,
                        intent,
                        state.copy(revision = state.revision + 1, phase = TowerPlayPhase.ACTIVE, runStarted = true),
                    )
                }

                TowerBattleLaunchResult.Unavailable ->
                    rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            }
        } finally {
            launchingPlayers.remove(playerId)
        }
    }

    /**
     * Unlocks the picked entries. A run under way picks again from its registered six; before its first battle the
     * registration is let go, so the challenger's party ([currentParty]) and the rules open again.
     */
    private fun changeTeam(
        session: Session,
        intent: TowerPlayIntent.ChangeTeam,
        currentParty: Collection<TowerPlayPartySlot>?,
    ): TowerPlayMutationResult {
        val state = session.state
        when (state.phase) {
            TowerPlayPhase.TEAM_LOCKED -> Unit
            TowerPlayPhase.ACTIVE -> return rejected(intent, state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            else -> return rejected(intent, state.revision, TowerPlayMessageKeys.NOTHING_TO_CHANGE)
        }
        if (state.runStarted) {
            session.lockedSelection = null
            return accept(session, intent, state.copy(revision = state.revision + 1, phase = TowerPlayPhase.CHANGING_TEAM))
        }
        releaseRegistration(session)
        val party = currentParty ?: state.party
        return accept(
            session,
            intent,
            state.copy(
                revision = state.revision + 1,
                phase = TowerPlayPhase.SELECTING,
                party = party,
                selectedPokemonIds = state.selectedPokemonOrder.keptIn(party),
                errorKeys = registrationErrors(party),
            ),
        )
    }

    /** Forfeits the battle under way from the Tower screen: a loss, which ends the run once the battle settles. */
    private fun forfeit(
        playerId: UUID,
        session: Session,
        intent: TowerPlayIntent.Forfeit,
    ): TowerPlayMutationResult {
        val battleId = session.activeBattleId
        if (session.state.phase != TowerPlayPhase.ACTIVE || battleId == null) {
            return rejected(intent, session.state.revision, TowerPlayMessageKeys.PHASE_INVALID)
        }
        if (!session.forfeitRequested &&
            requestForfeit(playerId, session, battleId, { battleForfeit(playerId, it) }, close = false) ==
            ForfeitRequest.UNAVAILABLE
        ) {
            return rejected(intent, session.state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
        }
        val state = session.state
        return accept(session, intent, state.copy(revision = state.revision + 1))
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
        if (!TowerPlayInteractionPolicy.canRetire(state)) {
            return rejected(intent, state.revision, TowerPlayMessageKeys.NOTHING_TO_RETIRE)
        }
        val track = TowerTrack(state.format, state.mode)
        runRetirementSink.retire(playerId, track)
        session.progressByTrack[track] = session.progressByTrack.getValue(track).copy(currentWinStreak = 0)
        return accept(session, intent, endRun(session, currentParty))
    }

    /**
     * Ends the session's run on its stored progress: back to selecting, with the registered team let go and the
     * rules open again. [party] is the challenger's party now, or the shown one when it was not read.
     */
    private fun endRun(session: Session, party: Collection<TowerPlayPartySlot>?): TowerPlayViewState {
        releaseRegistration(session)
        val state = session.state
        val shownParty = party ?: state.party
        val progress = session.progressByTrack.getValue(TowerTrack(state.format, state.mode))
        return state.copy(
            revision = state.revision + 1,
            phase = TowerPlayPhase.SELECTING,
            party = shownParty,
            selectedPokemonIds = emptySet(),
            currentWinStreak = progress.currentWinStreak,
            bestWinStreak = progress.bestWinStreak,
            errorKeys = registrationErrors(shownParty),
            runStarted = false,
            endlessUnlocked = state.endlessUnlocked || TowerTrack.endlessUnlocked(session.progressByTrack),
        ).also { session.state = it }
    }

    /**
     * Lets the registered six go, with what the opponents' AI learned of them over the run. The stored snapshot stays
     * until the next lock replaces it or the session closes, so a run that ends inside a battle's settlement cannot
     * fail on its cleanup after the result was recorded.
     */
    private fun releaseRegistration(session: Session) {
        session.lockedSelection = null
        session.runScopeId?.let(BattleTacticalRunMemoryStore::discard)
        session.runScopeId = null
    }

    private fun accept(
        session: Session,
        intent: TowerPlayIntent,
        state: TowerPlayViewState,
    ): TowerPlayMutationResult.Accepted {
        session.state = state
        return TowerPlayMutationResult.Accepted(intent.requestId, state)
    }

    private fun removeSession(playerId: UUID): Session? = sessions.remove(playerId)?.also { removed ->
        removed.runScopeId?.let(BattleTacticalRunMemoryStore::discard)
    }

    private fun removeSessionAndDiscardSnapshot(
        playerId: UUID,
        primaryFailure: Throwable? = null,
    ): Session? {
        var removed: Session? = null
        var failure = primaryFailure
        // A streak left standing would go on with whatever six the challenger registers next.
        sessions[playerId]?.progressByTrack?.filterValues { it.currentWinStreak > 0 }?.keys?.forEach { track ->
            try {
                runRetirementSink.retire(playerId, track)
            } catch (retirementFailure: Throwable) {
                if (failure == null) failure = retirementFailure else if (failure !== retirementFailure) {
                    failure.addSuppressed(retirementFailure)
                }
            }
        }
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

    private enum class ForfeitRequest { REQUESTED, UNAVAILABLE, SETTLED }

    private class Session(
        progressByTrack: Map<TowerTrack, TowerProgress>,
        val entryContext: TowerPlayEntryContext,
        var state: TowerPlayViewState,
    ) {
        val progressByTrack: MutableMap<TowerTrack, TowerProgress> = progressByTrack.toMutableMap()
        /** The entries checked at the last lock, which the next battle fields. */
        var lockedSelection: TowerSelectedTeam? = null
        var activeBattleId: UUID? = null
        /** The run's scope for what the opponents' AI learns of the challenger, from its first battle to its end. */
        var runScopeId: UUID? = null
        /** The battle under way was given up: however it ends, it counts as a loss. */
        var forfeitRequested: Boolean = false
        /** The session closes once the battle under way settles. */
        var closeAfterBattle: Boolean = false
        val responses: LinkedHashMap<UUID, CachedResponse> = LinkedHashMap()
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
    legendaryClassAllowed: Boolean = false,
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
    legendaryClassAllowed = legendaryClassAllowed,
)

/** The picked entries still in [party], in their order. */
private fun List<UUID>.keptIn(party: Collection<TowerPlayPartySlot>): List<UUID> {
    val ids = party.mapTo(HashSet(), TowerPlayPartySlot::pokemonId)
    return filter(ids::contains)
}

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
