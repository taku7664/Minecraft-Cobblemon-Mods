package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat

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

    fun changeMechanic(mechanic: MajorBattleMechanic): Boolean = submit { requestId ->
        TowerPlayIntent.ChangeMechanic(requestId, state.entryContextId, state.revision, mechanic)
    }

    fun lockTeam(): Boolean = submit { requestId ->
        TowerPlayIntent.LockTeam(requestId, state.entryContextId, state.revision)
    }

    fun start(): Boolean = submit { requestId ->
        TowerPlayIntent.Start(requestId, state.entryContextId, state.revision)
    }

    fun resume(): Boolean = submit { requestId ->
        TowerPlayIntent.Resume(requestId, state.entryContextId, state.revision)
    }

    fun abandon(): Boolean = submit { requestId ->
        TowerPlayIntent.Abandon(requestId, state.entryContextId, state.revision)
    }

    fun apply(result: TowerPlayMutationResult) {
        val pending = pendingIntent ?: return
        if (result.requestId != pending.requestId) return

        when (result) {
            is TowerPlayMutationResult.Accepted -> {
                if (result.state.entryContextId != state.entryContextId) return
                if (result.state.revision <= state.revision) return
                state = result.state
                feedbackKey = null
                fieldFeedbackKeys = emptyList()
                pendingIntent = null
            }

            is TowerPlayMutationResult.Rejected -> {
                if (result.currentRevision < state.revision) return
                feedbackKey = result.messageKey
                fieldFeedbackKeys = result.fieldErrors.values.distinct()
                pendingIntent = null
            }
        }
    }

    private fun submit(createIntent: (UUID) -> TowerPlayIntent): Boolean {
        if (pendingIntent != null) return false
        val intent = createIntent(requestIdFactory())
        pendingIntent = intent
        feedbackKey = null
        fieldFeedbackKeys = emptyList()
        sendIntent(intent)
        return true
    }
}

internal object TowerPlayInteractionPolicy {
    fun canRequestLock(state: TowerPlayViewState, isPending: Boolean): Boolean =
        state.phase == TowerPlayPhase.SELECTING &&
            !isPending &&
            state.selectedMechanic != null &&
            state.selectedPokemonOrder.size == state.format.selectionSize
}
