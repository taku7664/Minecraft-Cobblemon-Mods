package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import java.util.UUID

internal enum class FactoryPlayPhase {
    AVAILABLE,
    INITIAL_DRAFT,
    READY,
    IN_BATTLE,
    SWAP_DECISION,
    ROUND_DRAFT,
    COMPLETE,
}

internal class FactoryPlayView(
    val playerId: UUID,
    val phase: FactoryPlayPhase,
    val format: FactoryBattleFormat?,
    val levelMode: FactoryLevelMode?,
    val wins: Int,
    val rentAndTradeCount: Int,
    teamSets: Collection<FactoryRentalSet> = emptyList(),
    draftSets: Collection<FactoryRentalSet> = emptyList(),
    swapOffers: Collection<FactorySwapOffer> = emptyList(),
    val activeBattleId: UUID? = null,
    val canReviseSelection: Boolean = false,
) {
    val teamSets: List<FactoryRentalSet> = Collections.unmodifiableList(ArrayList(teamSets))
    val draftSets: List<FactoryRentalSet> = Collections.unmodifiableList(ArrayList(draftSets))
    val swapOffers: List<FactorySwapOffer> = Collections.unmodifiableList(ArrayList(swapOffers))
}

internal enum class FactoryPlayError {
    CATALOG_UNAVAILABLE,
    ALREADY_ACTIVE,
    NO_RUN,
    WRONG_PHASE,
    INVALID_SELECTION,
    BATTLE_UNAVAILABLE,
}

internal sealed interface FactoryPlayResult {
    data class Accepted(val view: FactoryPlayView) : FactoryPlayResult
    data class Rejected(val error: FactoryPlayError) : FactoryPlayResult
}

internal class FactoryPlayService(
    private val catalogSource: () -> FactoryCatalog?,
    private val sessions: FactorySessionService,
    private val random: FactoryCatalogRandom,
) {
    private val pendingStarts = HashMap<UUID, PendingStart>()
    private val previousDraftIds = HashMap<UUID, Set<String>>()

    @Synchronized
    fun status(playerId: UUID): FactoryPlayView = current(playerId)

    @Synchronized
    fun start(
        playerId: UUID,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): FactoryPlayResult {
        if (playerId in pendingStarts || sessions.snapshot(playerId) != null) {
            return FactoryPlayResult.Rejected(FactoryPlayError.ALREADY_ACTIVE)
        }
        val catalog = catalogSource() ?: return FactoryPlayResult.Rejected(FactoryPlayError.CATALOG_UNAVAILABLE)
        val draft = FactoryDraftSelector(catalog, random).select(
            levelMode,
            round = 1,
            rentAndTradeCount = 1,
            previousSetIds = previousDraftIds[playerId].orEmpty(),
        )
            ?: return FactoryPlayResult.Rejected(FactoryPlayError.CATALOG_UNAVAILABLE)
        previousDraftIds[playerId] = draft.sets.mapTo(linkedSetOf(), FactoryRentalSet::setId)
        pendingStarts[playerId] = PendingStart(format, levelMode, draft)
        return FactoryPlayResult.Accepted(current(playerId))
    }

    @Synchronized
    fun selectDraft(playerId: UUID, setIds: List<String>): FactoryPlayResult {
        val pending = pendingStarts[playerId]
        if (pending != null) {
            val resolvedSetIds = resolveVisibleDraftSelection(setIds, pending.draft.sets)
                ?: return FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
            val team = try {
                pending.draft.select(resolvedSetIds, pending.format)
            } catch (_: IllegalArgumentException) {
                return FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
            } catch (_: IllegalStateException) {
                return FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
            }
            val result = sessions.start(playerId, team, pending.levelMode, initialDraft = pending.draft, healRentals = {})
            if (result !is FactorySessionStartResult.Started) {
                return FactoryPlayResult.Rejected(FactoryPlayError.ALREADY_ACTIVE)
            }
            pendingStarts.remove(playerId)
            return FactoryPlayResult.Accepted(current(playerId))
        }
        val snapshot = sessions.snapshot(playerId)
        val resolvedSetIds = snapshot?.pendingDraftSets?.let { resolveVisibleDraftSelection(setIds, it) }
            ?: setIds
        val selected = try {
            sessions.selectDraft(playerId, resolvedSetIds)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        return if (selected) {
            FactoryPlayResult.Accepted(current(playerId))
        } else {
            FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
        }
    }

    @Synchronized
    fun beginBattle(playerId: UUID, orderedSetIds: List<String>? = null): FactoryPlayResult {
        val snapshot = sessions.snapshot(playerId) ?: return FactoryPlayResult.Rejected(FactoryPlayError.NO_RUN)
        if (snapshot.phase != FactoryRunPhase.READY) return FactoryPlayResult.Rejected(FactoryPlayError.WRONG_PHASE)
        val requestedOrder = orderedSetIds ?: snapshot.teamSets.map(FactoryRentalSet::setId)
        if (!sessions.reorderTeam(playerId, requestedOrder)) {
            return FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
        }
        val catalog = catalogSource() ?: return FactoryPlayResult.Rejected(FactoryPlayError.CATALOG_UNAVAILABLE)
        val round = FactoryProgression.roundForBattle(snapshot.wins + 1)
        val opponent = FactoryOpponentSelector(catalog, random)
            .select(snapshot.format, snapshot.levelMode, round)
        if (opponent !is FactoryOpponentSelectionResult.Selected) {
            return FactoryPlayResult.Rejected(FactoryPlayError.CATALOG_UNAVAILABLE)
        }
        val opponentByToken = opponent.team.associateByTo(LinkedHashMap()) { UUID.randomUUID() }
        val launched = sessions.beginBattle(
            playerId = playerId,
            opponentTeam = opponentByToken,
            trainerNameKey = opponent.concept.displayNameKey,
            aiSkill = opponent.concept.aiSkill,
            strategyBrief = opponent.strategy,
        )
        return if (launched is FactoryBattleLaunchResult.Started) {
            FactoryPlayResult.Accepted(current(playerId))
        } else {
            FactoryPlayResult.Rejected(FactoryPlayError.BATTLE_UNAVAILABLE)
        }
    }

    @Synchronized
    fun reviseSelection(playerId: UUID): FactoryPlayResult = if (sessions.reviseSelection(playerId)) {
        FactoryPlayResult.Accepted(current(playerId))
    } else {
        FactoryPlayResult.Rejected(FactoryPlayError.WRONG_PHASE)
    }

    @Synchronized
    fun keepTeam(playerId: UUID): FactoryPlayResult = if (sessions.keepTeam(playerId)) {
        FactoryPlayResult.Accepted(current(playerId))
    } else {
        FactoryPlayResult.Rejected(FactoryPlayError.WRONG_PHASE)
    }

    @Synchronized
    fun swap(playerId: UUID, outgoingSetId: String, incomingToken: UUID): FactoryPlayResult {
        val swapped = try {
            sessions.swap(playerId, outgoingSetId, incomingToken)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        return if (swapped) {
            FactoryPlayResult.Accepted(current(playerId))
        } else {
            FactoryPlayResult.Rejected(FactoryPlayError.INVALID_SELECTION)
        }
    }

    @Synchronized
    fun abandon(playerId: UUID): FactoryPlayView {
        pendingStarts.remove(playerId)
        sessions.close(playerId)
        return available(playerId)
    }

    @Synchronized
    fun disconnect(playerId: UUID) {
        pendingStarts.remove(playerId)
        previousDraftIds.remove(playerId)
        sessions.close(playerId)
    }

    private fun current(playerId: UUID): FactoryPlayView {
        val pending = pendingStarts[playerId]
        if (pending != null) {
            return FactoryPlayView(
                playerId = playerId,
                phase = FactoryPlayPhase.INITIAL_DRAFT,
                format = pending.format,
                levelMode = pending.levelMode,
                wins = 0,
                rentAndTradeCount = 1,
                draftSets = pending.draft.sets,
            )
        }
        val snapshot = sessions.snapshot(playerId) ?: return available(playerId)
        return FactoryPlayView(
            playerId = playerId,
            phase = if (snapshot.phase == FactoryRunPhase.DRAFT_SELECTION && snapshot.wins == 0) {
                FactoryPlayPhase.INITIAL_DRAFT
            } else {
                snapshot.phase.toPlayPhase()
            },
            format = snapshot.format,
            levelMode = snapshot.levelMode,
            wins = snapshot.wins,
            rentAndTradeCount = snapshot.rentAndTradeCount,
            teamSets = snapshot.teamSets,
            draftSets = snapshot.pendingDraftSets ?: emptyList(),
            swapOffers = snapshot.swapOffers,
            activeBattleId = snapshot.activeBattleId,
            canReviseSelection = snapshot.canReviseSelection,
        )
    }

    private fun available(playerId: UUID) = FactoryPlayView(
        playerId = playerId,
        phase = FactoryPlayPhase.AVAILABLE,
        format = null,
        levelMode = null,
        wins = 0,
        rentAndTradeCount = 0,
    )

    private fun resolveVisibleDraftSelection(
        selection: List<String>,
        visibleDraft: List<FactoryRentalSet>,
    ): List<String>? = selection.map { value ->
        visibleDraft.firstOrNull { it.setId == value }?.setId
            ?: value.toIntOrNull()?.takeIf { it in 1..visibleDraft.size }?.let { visibleDraft[it - 1].setId }
            ?: return null
    }

    private fun FactoryRunPhase.toPlayPhase(): FactoryPlayPhase = when (this) {
        FactoryRunPhase.READY -> FactoryPlayPhase.READY
        FactoryRunPhase.IN_BATTLE -> FactoryPlayPhase.IN_BATTLE
        FactoryRunPhase.SWAP_DECISION -> FactoryPlayPhase.SWAP_DECISION
        FactoryRunPhase.DRAFT_SELECTION -> FactoryPlayPhase.ROUND_DRAFT
        FactoryRunPhase.COMPLETE -> FactoryPlayPhase.COMPLETE
    }

    private data class PendingStart(
        val format: FactoryBattleFormat,
        val levelMode: FactoryLevelMode,
        val draft: FactoryRentalDraft,
    )
}
