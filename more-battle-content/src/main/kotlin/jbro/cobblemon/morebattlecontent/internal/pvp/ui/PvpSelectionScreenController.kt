package jbro.cobblemon.morebattlecontent.internal.pvp.ui

import java.util.Collections
import java.util.UUID

internal class PvpSelectionScreenController(
    initialState: PvpSelectionViewState,
    private val send: (PvpSelectionIntent) -> Unit,
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    var state: PvpSelectionViewState = initialState
        private set
    private val selected = LinkedHashSet(initialState.selectedPokemonIds)
    private var pendingRequestId: UUID? = null
    var feedbackKey: String? = null
        private set

    val selectedPokemonIds: Set<UUID>
        get() = Collections.unmodifiableSet(LinkedHashSet(selected))

    val isPending: Boolean
        get() = pendingRequestId != null

    fun toggle(pokemonId: UUID): Boolean {
        if (state.spectatorMode || isPending || state.waitingForOpponent || state.ownParty.none { it.pokemonId == pokemonId }) return false
        feedbackKey = null
        if (pokemonId in selected) {
            selected -= pokemonId
            return true
        }
        if (selected.size >= state.format.selectionSize) return false
        selected += pokemonId
        return true
    }

    fun submit(): Boolean {
        if (state.spectatorMode || isPending || state.waitingForOpponent || selected.size != state.format.selectionSize) return false
        return send(PvpSelectionIntent.Submit(nextRequestId(), state.matchId, selected.toList()))
    }

    fun retry(): Boolean {
        if (state.spectatorMode || isPending || selected.size != state.format.selectionSize) return false
        return send(PvpSelectionIntent.Retry(nextRequestId(), state.matchId))
    }

    fun unready(): Boolean {
        if (state.spectatorMode || isPending || !state.waitingForOpponent) return false
        return send(PvpSelectionIntent.Unready(nextRequestId(), state.matchId))
    }

    fun cancel(): Boolean {
        if (state.spectatorMode || isPending) return false
        return send(PvpSelectionIntent.Cancel(nextRequestId(), state.matchId))
    }

    fun applyAccepted(requestId: UUID, accepted: PvpSelectionViewState) {
        if (pendingRequestId != requestId || accepted.matchId != state.matchId) return
        pendingRequestId = null
        feedbackKey = null
        state = accepted
        selected.clear()
        selected += accepted.selectedPokemonIds
    }

    fun applyRejected(requestId: UUID, messageKey: String) {
        if (pendingRequestId != requestId) return
        pendingRequestId = null
        feedbackKey = messageKey
    }

    private fun nextRequestId(): UUID = requestIdFactory().also { pendingRequestId = it }

    private fun send(intent: PvpSelectionIntent): Boolean {
        send.invoke(intent)
        return true
    }
}
