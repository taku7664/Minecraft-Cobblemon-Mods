package jbro.cobblemon.mcc.internal.pvp.ui

import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent

internal class PvpRoomScreenController(
    initialState: PvpRoomClientView,
    private val send: (PvpRoomIntent) -> Unit,
) {
    var state: PvpRoomClientView = initialState
        private set
    var feedbackKey: String? = null
        private set
    var feedbackArgs: List<String> = emptyList()
        private set
    private var pendingRequestId: UUID? = null

    val isPending: Boolean
        get() = pendingRequestId != null

    fun submit(intent: PvpRoomIntent): Boolean {
        if (isPending) return false
        pendingRequestId = intent.requestId
        feedbackKey = null
        try {
            send(intent)
        } catch (failure: Throwable) {
            if (pendingRequestId == intent.requestId) pendingRequestId = null
            throw failure
        }
        return true
    }

    fun applyState(requestId: UUID?, next: PvpRoomClientView) {
        state = next
        if (requestId == pendingRequestId) {
            pendingRequestId = null
            feedbackKey = null
        }
    }

    fun applyRejected(requestId: UUID, messageKey: String, messageArgs: List<String> = emptyList()) {
        if (requestId != pendingRequestId) return
        pendingRequestId = null
        feedbackKey = messageKey
        feedbackArgs = messageArgs
    }
}
