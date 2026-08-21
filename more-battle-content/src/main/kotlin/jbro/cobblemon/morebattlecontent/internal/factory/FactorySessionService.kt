package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief

internal class FactorySessionSnapshot(
    val runId: UUID,
    val format: FactoryBattleFormat,
    val levelMode: FactoryLevelMode,
    val wins: Int,
    val rentAndTradeCount: Int,
    val phase: FactoryRunPhase,
    val activeBattleId: UUID?,
    teamSets: Collection<FactoryRentalSet>,
    swapOffers: Collection<FactorySwapOffer>,
    pendingDraftSets: Collection<FactoryRentalSet>?,
    val canReviseSelection: Boolean,
) {
    val teamSets: List<FactoryRentalSet> = Collections.unmodifiableList(ArrayList(teamSets))
    val swapOffers: List<FactorySwapOffer> = Collections.unmodifiableList(ArrayList(swapOffers))
    val pendingDraftSets: List<FactoryRentalSet>? = pendingDraftSets?.let { Collections.unmodifiableList(ArrayList(it)) }
}

internal sealed interface FactorySessionStartResult {
    data class Started(val runId: UUID) : FactorySessionStartResult
    data object AlreadyActive : FactorySessionStartResult
}

internal sealed interface FactorySessionCompletionResult {
    data class Completed(val result: FactoryBattleCompletionResult) : FactorySessionCompletionResult
    data object NoSession : FactorySessionCompletionResult
    data class StaleRun(val activeRunId: UUID) : FactorySessionCompletionResult
}

internal class FactorySessionService(
    private val runBattles: FactoryRunBattleService,
    private val completions: FactoryBattleCompletionService,
    private val draftProvider: (UUID, FactoryLevelMode, round: Int, rentAndTradeCount: Int) -> FactoryRentalDraft? =
        { _, _, _, _ -> null },
) {
    private val sessions = HashMap<UUID, FactoryRunSession>()

    @Synchronized
    fun start(
        playerId: UUID,
        team: FactoryRentalTeam,
        levelMode: FactoryLevelMode,
        initialDraft: FactoryRentalDraft? = null,
        runId: UUID = UUID.randomUUID(),
        healRentals: (FactoryRentalTeam) -> Unit,
    ): FactorySessionStartResult {
        if (sessions.containsKey(playerId)) return FactorySessionStartResult.AlreadyActive
        sessions[playerId] = FactoryRunSession(
            runId,
            team,
            levelMode,
            healRentals,
            initialDraft,
            { mode, round, trades -> draftProvider(playerId, mode, round, trades) },
        )
        return FactorySessionStartResult.Started(runId)
    }

    @Synchronized
    fun snapshot(playerId: UUID): FactorySessionSnapshot? = sessions[playerId]?.snapshot()

    @Synchronized
    fun beginBattle(
        playerId: UUID,
        opponentTeam: Map<UUID, FactoryRentalSet>,
        trainerNameKey: String,
        aiSkill: Int,
        strategyBrief: BattleStrategyBrief,
    ): FactoryBattleLaunchResult {
        val session = sessions[playerId] ?: return FactoryBattleLaunchResult.Unavailable
        return runBattles.begin(playerId, session, opponentTeam, trainerNameKey, aiSkill, strategyBrief)
    }

    @Synchronized
    fun reorderTeam(playerId: UUID, orderedSetIds: List<String>): Boolean =
        sessions[playerId]?.reorderTeam(orderedSetIds) == true

    @Synchronized
    fun completeVictory(
        playerId: UUID,
        runId: UUID,
        battleId: UUID,
        opponentSets: Map<UUID, FactoryRentalSet>,
        observations: Map<String, FactoryOpponentObservation>,
    ): FactorySessionCompletionResult = withRun(playerId, runId) { session ->
        FactorySessionCompletionResult.Completed(
            completions.completeVictory(playerId, session, battleId, opponentSets, observations),
        )
    }

    @Synchronized
    fun completeLoss(
        playerId: UUID,
        runId: UUID,
        battleId: UUID,
    ): FactorySessionCompletionResult = withRun(playerId, runId) { session ->
        FactorySessionCompletionResult.Completed(completions.completeLoss(playerId, session, battleId))
    }

    @Synchronized
    fun cancelBattle(
        playerId: UUID,
        runId: UUID,
        battleId: UUID,
    ): FactorySessionCompletionResult = withRun(playerId, runId) { session ->
        FactorySessionCompletionResult.Completed(completions.cancel(session, battleId))
    }

    @Synchronized
    fun keepTeam(playerId: UUID): Boolean {
        val session = sessions[playerId] ?: return false
        if (session.phase != FactoryRunPhase.SWAP_DECISION) return false
        session.keepTeam()
        return true
    }

    @Synchronized
    fun swap(playerId: UUID, outgoingSetId: String, incomingToken: UUID): Boolean {
        val session = sessions[playerId] ?: return false
        if (session.phase != FactoryRunPhase.SWAP_DECISION) return false
        session.swap(outgoingSetId, incomingToken)
        return true
    }

    @Synchronized
    fun selectDraft(playerId: UUID, setIds: List<String>): Boolean {
        val session = sessions[playerId] ?: return false
        if (session.phase != FactoryRunPhase.DRAFT_SELECTION || session.pendingDraft == null) return false
        session.selectDraft(setIds)
        return true
    }

    @Synchronized
    fun reviseSelection(playerId: UUID): Boolean {
        val session = sessions[playerId] ?: return false
        if (!session.canReviseSelection) return false
        session.reviseSelection()
        return true
    }

    @Synchronized
    fun close(playerId: UUID): Boolean = sessions.remove(playerId) != null

    private inline fun withRun(
        playerId: UUID,
        runId: UUID,
        action: (FactoryRunSession) -> FactorySessionCompletionResult,
    ): FactorySessionCompletionResult {
        val session = sessions[playerId] ?: return FactorySessionCompletionResult.NoSession
        if (session.runId != runId) return FactorySessionCompletionResult.StaleRun(session.runId)
        return action(session)
    }

    private fun FactoryRunSession.snapshot() = FactorySessionSnapshot(
        runId = runId,
        format = team.format,
        levelMode = levelMode,
        wins = wins,
        rentAndTradeCount = rentAndTradeCount,
        phase = phase,
        activeBattleId = activeBattleId,
        teamSets = team.sets,
        swapOffers = swapOffers.toList(),
        pendingDraftSets = pendingDraft?.sets,
        canReviseSelection = this.canReviseSelection,
    )
}
