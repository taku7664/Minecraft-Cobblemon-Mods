package jbro.cobblemon.mcc.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerMode

internal class TowerPlayScreenController(
    initialState: TowerPlayViewState,
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
    private val sendIntent: (TowerPlayIntent) -> Unit,
) {
    var state: TowerPlayViewState = initialState
        private set

    var feedbackKey: String? = null
        private set

    var fieldFeedbackKeys: List<String> = emptyList()
        private set

    private var pendingIntent: TowerPlayIntent? = null

    val isPending: Boolean
        get() = pendingIntent != null

    fun toggleSelection(pokemonId: UUID): Boolean = submit { requestId ->
        TowerPlayIntent.ToggleSelection(requestId, state.entryContextId, state.revision, pokemonId)
    }

    fun changeFormat(format: TowerBattleFormat): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeFormat(requestId, state.entryContextId, state.revision, format)
    }

    fun changeMode(mode: TowerMode): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeMode(requestId, state.entryContextId, state.revision, mode)
    }

    fun changeMechanic(mechanic: MajorBattleMechanic): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeMechanic(requestId, state.entryContextId, state.revision, mechanic)
    }

    fun changeLegendaryClassAllowed(allowed: Boolean): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeLegendaryClassAllowed(requestId, state.entryContextId, state.revision, allowed)
    }

    fun lockTeam(): Boolean = submit { requestId ->
        TowerPlayIntent.LockTeam(requestId, state.entryContextId, state.revision)
    }

    fun start(): Boolean = submit { requestId ->
        TowerPlayIntent.Start(requestId, state.entryContextId, state.revision)
    }

    fun retire(): Boolean = submit { requestId ->
        TowerPlayIntent.Retire(requestId, state.entryContextId, state.revision)
    }

    fun changeTeam(): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeTeam(requestId, state.entryContextId, state.revision)
    }

    fun forfeit(): Boolean = submit { requestId ->
        TowerPlayIntent.Forfeit(requestId, state.entryContextId, state.revision)
    }

    fun apply(result: TowerPlayMutationResult) {
        val pending = pendingIntent ?: return
        if (result.requestId != pending.requestId) return
        // The answer to the request in flight always frees the screen; only a newer state replaces the shown one.
        pendingIntent = null
        when (result) {
            is TowerPlayMutationResult.Accepted -> {
                if (result.state.entryContextId != state.entryContextId || result.state.revision <= state.revision) return
                state = result.state
                feedbackKey = null
                fieldFeedbackKeys = emptyList()
            }

            is TowerPlayMutationResult.Rejected -> {
                feedbackKey = result.messageKey
                fieldFeedbackKeys = result.fieldErrors.values.distinct()
            }
        }
    }

    private fun submit(createIntent: (UUID) -> TowerPlayIntent): Boolean {
        if (pendingIntent != null) return false
        val intent = createIntent(requestIdFactory())
        pendingIntent = intent
        feedbackKey = null
        fieldFeedbackKeys = emptyList()
        try {
            sendIntent(intent)
        } catch (failure: Throwable) {
            if (pendingIntent === intent) pendingIntent = null
            throw failure
        }
        return true
    }
}

internal object TowerPlayInteractionPolicy {
    /** The entries are picked: on a fresh team, or again from the registered six between battles. */
    fun picking(state: TowerPlayViewState): Boolean =
        state.phase == TowerPlayPhase.SELECTING || state.phase == TowerPlayPhase.CHANGING_TEAM

    /** The six, the mode, the format and the session rules change only while no team is registered. */
    fun rulesOpen(state: TowerPlayViewState): Boolean = state.phase == TowerPlayPhase.SELECTING

    fun canRequestLock(state: TowerPlayViewState, isPending: Boolean): Boolean =
        picking(state) &&
            !isPending &&
            state.selectedMechanic != null &&
            state.selectedPokemonOrder.size == state.format.selectionSize

    /**
     * A run can be given up between battles once it is under way, or while a streak stands from before this session
     * (an operator's, or one a crash left behind).
     */
    fun canRetire(state: TowerPlayViewState): Boolean =
        state.phase != TowerPlayPhase.ACTIVE && (state.runStarted || state.currentWinStreak > 0)
}
