package jbro.cobblemon.morebattlecontent.internal.factory.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayPhase
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayView

internal sealed interface FactoryPlayIntent {
    val requestId: UUID

    data class Start(
        override val requestId: UUID,
        val format: FactoryBattleFormat,
        val levelMode: FactoryLevelMode,
    ) : FactoryPlayIntent

    data class SelectRentals(
        override val requestId: UUID,
        val setIds: List<String>,
    ) : FactoryPlayIntent

    data class ReviseSelection(override val requestId: UUID) : FactoryPlayIntent
    data class BeginBattle(
        override val requestId: UUID,
        val orderedSetIds: List<String>,
    ) : FactoryPlayIntent
    data class KeepTeam(override val requestId: UUID) : FactoryPlayIntent

    data class Swap(
        override val requestId: UUID,
        val outgoingSetId: String,
        val incomingToken: UUID,
    ) : FactoryPlayIntent

    data class Abandon(override val requestId: UUID) : FactoryPlayIntent
}

internal class FactoryPlayScreenController(
    initialState: FactoryPlayView,
    private val sendIntent: (FactoryPlayIntent) -> Unit,
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    var state: FactoryPlayView = initialState
        private set
    var chosenFormat: FactoryBattleFormat = initialState.format ?: FactoryBattleFormat.SINGLE
        private set
    var chosenLevelMode: FactoryLevelMode = initialState.levelMode ?: FactoryLevelMode.LEVEL_50
        private set
    var feedbackKey: String? = null
        private set

    private val selectedRentalIds = LinkedHashSet<String>()
    private val battleOrderRentalIds = LinkedHashSet<String>()
    private var selectedOutgoingSetId: String? = null
    private var selectedIncomingToken: UUID? = null
    private var pendingRequestId: UUID? = null

    val isPending: Boolean
        get() = pendingRequestId != null
    val selectedSetIds: Set<String>
        get() = selectedRentalIds.toSet()
    val battleOrderSetIds: List<String>
        get() = battleOrderRentalIds.toList()
    val outgoingSetId: String?
        get() = selectedOutgoingSetId
    val incomingToken: UUID?
        get() = selectedIncomingToken

    fun chooseFormat(format: FactoryBattleFormat): Boolean {
        if (isPending || state.phase != FactoryPlayPhase.AVAILABLE) return false
        chosenFormat = format
        selectedRentalIds.clear()
        return true
    }

    fun chooseLevelMode(levelMode: FactoryLevelMode): Boolean {
        if (isPending || state.phase != FactoryPlayPhase.AVAILABLE) return false
        chosenLevelMode = levelMode
        return true
    }

    fun toggleRental(setId: String): Boolean {
        if (isPending || state.phase !in DRAFT_PHASES || state.draftSets.none { it.setId == setId }) return false
        if (!selectedRentalIds.remove(setId)) {
            val required = requireNotNull(state.format).selectionSize
            if (selectedRentalIds.size >= required) return false
            selectedRentalIds += setId
        }
        return true
    }

    fun chooseOutgoing(setId: String): Boolean {
        if (isPending || state.phase != FactoryPlayPhase.SWAP_DECISION || state.teamSets.none { it.setId == setId }) {
            return false
        }
        selectedOutgoingSetId = if (selectedOutgoingSetId == setId) null else setId
        return true
    }

    fun chooseIncoming(token: UUID): Boolean {
        if (isPending || state.phase != FactoryPlayPhase.SWAP_DECISION || state.swapOffers.none { it.token == token }) {
            return false
        }
        selectedIncomingToken = if (selectedIncomingToken == token) null else token
        return true
    }

    fun toggleBattleOrder(setId: String): Boolean {
        if (isPending || state.phase != FactoryPlayPhase.READY || state.teamSets.none { it.setId == setId }) return false
        if (!battleOrderRentalIds.remove(setId)) {
            if (battleOrderRentalIds.size >= state.teamSets.size) return false
            battleOrderRentalIds += setId
        }
        return true
    }

    fun start(): Boolean = submit {
        if (state.phase != FactoryPlayPhase.AVAILABLE) return@submit null
        FactoryPlayIntent.Start(it, chosenFormat, chosenLevelMode)
    }

    fun confirmSelection(): Boolean = submit { requestId ->
        val required = state.format?.selectionSize ?: return@submit null
        if (state.phase !in DRAFT_PHASES || selectedRentalIds.size != required) return@submit null
        val visibleOrder = state.draftSets.map { it.setId }.filter(selectedRentalIds::contains)
        FactoryPlayIntent.SelectRentals(requestId, visibleOrder)
    }

    fun beginBattle(): Boolean = submit {
        if (state.phase == FactoryPlayPhase.READY && battleOrderRentalIds.size == state.teamSets.size) {
            FactoryPlayIntent.BeginBattle(it, battleOrderRentalIds.toList())
        } else {
            null
        }
    }

    fun reviseSelection(): Boolean = submit {
        if (state.phase == FactoryPlayPhase.READY && state.canReviseSelection) {
            FactoryPlayIntent.ReviseSelection(it)
        } else {
            null
        }
    }

    fun keepTeam(): Boolean = submit {
        if (state.phase == FactoryPlayPhase.SWAP_DECISION) FactoryPlayIntent.KeepTeam(it) else null
    }

    fun swap(): Boolean = submit { requestId ->
        if (state.phase != FactoryPlayPhase.SWAP_DECISION) return@submit null
        FactoryPlayIntent.Swap(
            requestId,
            selectedOutgoingSetId ?: return@submit null,
            selectedIncomingToken ?: return@submit null,
        )
    }

    fun abandon(): Boolean = submit { FactoryPlayIntent.Abandon(it) }

    fun applyAccepted(requestId: UUID?, next: FactoryPlayView) {
        if (requestId != null && requestId != pendingRequestId) return
        state = next
        pendingRequestId = null
        feedbackKey = null
        selectedRentalIds.clear()
        battleOrderRentalIds.clear()
        selectedOutgoingSetId = null
        selectedIncomingToken = null
        next.format?.let { chosenFormat = it }
        next.levelMode?.let { chosenLevelMode = it }
    }

    fun applyRejected(requestId: UUID, messageKey: String) {
        if (requestId != pendingRequestId) return
        pendingRequestId = null
        feedbackKey = messageKey
    }

    private fun submit(create: (UUID) -> FactoryPlayIntent?): Boolean {
        if (isPending) return false
        val requestId = requestIdFactory()
        val intent = create(requestId) ?: return false
        pendingRequestId = requestId
        feedbackKey = null
        sendIntent(intent)
        return true
    }

    private companion object {
        val DRAFT_PHASES = setOf(FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT)
    }
}
