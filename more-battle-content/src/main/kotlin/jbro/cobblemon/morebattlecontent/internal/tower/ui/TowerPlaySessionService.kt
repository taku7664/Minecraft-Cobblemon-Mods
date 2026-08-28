package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchRequest
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgressUpdate
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgression
import jbro.cobblemon.morebattlecontent.internal.tower.TowerTeamRegistrationIssue
import jbro.cobblemon.morebattlecontent.internal.tower.TowerTeamRegistrationResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerTeamRules
import jbro.cobblemon.morebattlecontent.internal.tower.TowerTeamSelectionIssue
import jbro.cobblemon.morebattlecontent.internal.tower.TowerTeamSelectionResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPokemonRegistration
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshots
import jbro.cobblemon.morebattlecontent.internal.tower.TowerSelectedTeam
import jbro.cobblemon.morebattlecontent.internal.tower.UnavailableTowerBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.tower.UnavailableTowerRegisteredTeamSnapshots
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalRunMemoryStore

internal class TowerPlayOpenRequest(
    party: Collection<TowerPlayPartySlot>,
    val initialFormat: TowerBattleFormat,
    progressByFormat: Map<TowerBattleFormat, TowerProgress>,
    val bpBalance: Long,
) {
    val party: List<TowerPlayPartySlot> = Collections.unmodifiableList(ArrayList(party))
    val progressByFormat: Map<TowerBattleFormat, TowerProgress> =
        Collections.unmodifiableMap(orderedProgressCopy(progressByFormat))

    init {
        require(this.progressByFormat.keys == TowerBattleFormat.entries.toSet()) {
            "Tower play state requires progress for every battle format"
        }
        require(this.progressByFormat.all { (format, progress) -> progress.format == format }) {
            "Tower progress must match its battle format key"
        }
        require(bpBalance >= 0) { "BP balance cannot be negative" }
    }
}

internal object TowerPlayMessageKeys {
    private const val PREFIX = "screen.cobblemon_more_battle_content.tower.error"

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

    const val PARTY_SIZE = "$PREFIX.party_size"
    const val DUPLICATE_POKEMON = "$PREFIX.duplicate_pokemon"
    const val DUPLICATE_SPECIES = "$PREFIX.duplicate_species"
    const val DUPLICATE_HELD_ITEM = "$PREFIX.duplicate_held_item"
    const val SELECTION_SIZE = "$PREFIX.selection_size"
    const val UNREGISTERED_POKEMON = "$PREFIX.unregistered_pokemon"
    const val LEGENDARY_CLASS_NOT_ALLOWED = "$PREFIX.legendary_class_not_allowed"

}

internal fun interface TowerPlayBattleCompletionSink {
    fun record(playerId: UUID, update: TowerProgressUpdate)
}

private val NoopTowerPlayBattleCompletionSink = TowerPlayBattleCompletionSink { _, _ -> }

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
    private val entryContextIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val sessions = HashMap<UUID, Session>()

    @Synchronized
    fun open(
        playerId: UUID,
        request: TowerPlayOpenRequest,
        entryContext: TowerPlayEntryContext = TowerPlayEntryContext.Command(entryContextIdFactory()),
    ): TowerPlayViewState {
        sessions[playerId]?.let { existing ->
            if (existing.state.phase != TowerPlayPhase.SELECTING) return existing.state
            val keepRegisteredTeam = existing.hasRegisteredSnapshot
            val progressByFormat = if (keepRegisteredTeam) {
                existing.progressByFormat
            } else {
                request.progressByFormat
            }
            val progress = progressByFormat.getValue(existing.state.format)
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
            )
            sessions[playerId] = Session(
                progressByFormat = progressByFormat,
                entryContext = entryContext,
                state = refreshed,
                lockedSelection = existing.lockedSelection,
                hasRegisteredSnapshot = keepRegisteredTeam,
            )
            return refreshed
        }
        registeredTeamSnapshots.discard(playerId)
        val progress = request.progressByFormat.getValue(request.initialFormat)
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
        )
        sessions[playerId] = Session(request.progressByFormat, entryContext, state)
        return state
    }

    @Synchronized
    fun current(playerId: UUID): TowerPlayViewState? = sessions[playerId]?.state

    @Synchronized
    fun entryContext(playerId: UUID): TowerPlayEntryContext? = sessions[playerId]?.entryContext

    @Synchronized
    fun progress(playerId: UUID): Map<TowerBattleFormat, TowerProgress>? =
        sessions[playerId]?.progressByFormat?.toMap()

    @Synchronized
    fun activeBattleId(playerId: UUID): UUID? = sessions[playerId]?.activeBattleId

    @Synchronized
    fun adminSetProgress(playerId: UUID, progress: TowerProgress): Boolean {
        val session = sessions[playerId] ?: return true
        if (session.activeBattleId != null && session.state.format == progress.format) return false
        session.progressByFormat[progress.format] = progress
        if (session.state.format == progress.format) {
            session.state = session.state.copy(
                revision = session.state.revision + 1,
                currentWinStreak = progress.currentWinStreak,
                bestWinStreak = progress.bestWinStreak,
            )
        }
        return true
    }

    @Synchronized
    fun refreshBpBalance(playerId: UUID, balance: Long): TowerPlayViewState? {
        require(balance >= 0) { "BP balance cannot be negative" }
        val session = sessions[playerId] ?: return null
        return session.state.copy(bpBalance = balance).also { session.state = it }
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
            registeredTeamSnapshots.discard(playerId)
            removeSession(playerId)
            return TowerSessionAbandonResult.SessionClosed
        }
        if (session.abandonRequested) return TowerSessionAbandonResult.ForfeitRequested(battleId)

        session.abandonRequested = true
        val requested = try {
            forfeit(battleId)
        } catch (exception: RuntimeException) {
            session.abandonRequested = false
            throw exception
        }
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
        val progress = session.progressByFormat.getValue(state.format)
        val recordedOutcome = if (session.abandonRequested) TowerBattleOutcome.LOSS else outcome
        val update = TowerProgression.record(progress, recordedOutcome)
        completionSink.record(playerId, update)

        session.progressByFormat[state.format] = update.after
        session.activeBattleId = null
        if (session.abandonRequested) {
            registeredTeamSnapshots.discard(playerId)
            removeSession(playerId)
            return TowerPlayBattleCompletionResult.SessionAbandoned
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
    fun close(playerId: UUID): Boolean {
        registeredTeamSnapshots.discard(playerId)
        return removeSession(playerId) != null
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
                try {
                    terminateBattle(battleId)
                } finally {
                    finishBattle(playerId, battleId, TowerBattleOutcome.LOSS, completionSink)
                }
            }
        } finally {
            registeredTeamSnapshots.discard(playerId)
            removeSession(playerId)
        }
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
            is TowerPlayIntent.ChangeMechanic -> changeMechanic(session, intent)
            is TowerPlayIntent.ChangeLegendaryClassAllowed -> changeLegendaryClassAllowed(session, intent)
            is TowerPlayIntent.LockTeam -> lockTeam(playerId, session, intent, currentParty)
            is TowerPlayIntent.Start -> startBattle(playerId, session, intent)
            is TowerPlayIntent.Resume -> rejected(intent, session.state.revision, TowerPlayMessageKeys.BATTLE_UNAVAILABLE)
            is TowerPlayIntent.Abandon -> abandon(playerId, session, intent)
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
        val progress = session.progressByFormat.getValue(intent.format)
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
        val progress = session.progressByFormat.getValue(state.format)
        val mechanic = checkNotNull(state.selectedMechanic) {
            "A locked Battle Tower team must retain its selected mechanic"
        }
        return when (
            val launch = battleLauncher.launch(
                TowerBattleLaunchRequest(
                    playerId,
                    progress,
                    selection,
                    mechanic,
                    state.legendaryClassAllowed,
                    session.state.entryContextId,
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
        progressByFormat: Map<TowerBattleFormat, TowerProgress>,
        val entryContext: TowerPlayEntryContext,
        var state: TowerPlayViewState,
        var lockedSelection: TowerSelectedTeam? = null,
        var activeBattleId: UUID? = null,
        var hasRegisteredSnapshot: Boolean = false,
        var abandonRequested: Boolean = false,
        val responses: LinkedHashMap<UUID, CachedResponse> = LinkedHashMap(),
    ) {
        val progressByFormat: MutableMap<TowerBattleFormat, TowerProgress> = progressByFormat.toMutableMap()
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
): TowerPlayViewState = TowerPlayViewState(
    entryContextId = entryContextId,
    revision = revision,
    format = progress.format,
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
}

private fun orderedProgressCopy(source: Map<TowerBattleFormat, TowerProgress>): LinkedHashMap<TowerBattleFormat, TowerProgress> =
    LinkedHashMap<TowerBattleFormat, TowerProgress>().apply {
        TowerBattleFormat.entries.forEach { format -> source[format]?.let { put(format, it) } }
    }

private const val MAX_CACHED_RESPONSES = 64
