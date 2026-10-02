package jbro.cobblemon.mcc.internal.tower.application

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.application.BattleApplicationRequestContext
import jbro.cobblemon.mcc.internal.application.BattleContentApplication
import jbro.cobblemon.mcc.internal.application.BattleContentDescriptor
import jbro.cobblemon.mcc.internal.application.BattleContentId
import jbro.cobblemon.mcc.internal.application.BattleContentPhase
import jbro.cobblemon.mcc.internal.application.BattleContentStatus
import jbro.cobblemon.mcc.internal.application.BattleFormatId
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerProgression
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerTrack
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.mcc.internal.tower.ui.TowerSessionAbandonResult

internal interface BattleTowerApplicationBackend {
    fun current(playerId: UUID): TowerPlayViewState?

    fun progress(playerId: UUID): Map<TowerTrack, TowerProgress>

    fun open(playerId: UUID, format: TowerBattleFormat): Boolean

    fun abandon(playerId: UUID): TowerSessionAbandonResult
}

internal class BattleTowerContentApplication(
    private val backend: BattleTowerApplicationBackend,
) : BattleContentApplication {
    override val descriptor = BattleContentDescriptor(
        contentId = CONTENT_ID,
        formats = TowerBattleFormat.entries.map { it.toApplicationId() },
    )

    override fun status(context: BattleApplicationRequestContext): BattleContentStatus =
        backend.current(context.playerId)?.toStatus(context.playerId) ?: available(context.playerId)

    override fun start(
        context: BattleApplicationRequestContext,
        formatId: BattleFormatId,
    ): BattleContentStatus {
        val format = formatId.toTowerFormat()
        check(backend.open(context.playerId, format)) { "Battle Tower screen is unavailable" }
        return checkNotNull(backend.current(context.playerId)) {
            "Battle Tower screen opened without creating a server session"
        }.toStatus(context.playerId)
    }

    override fun resume(context: BattleApplicationRequestContext): BattleContentStatus {
        val format = backend.current(context.playerId)?.format ?: TowerBattleFormat.SINGLE
        check(backend.open(context.playerId, format)) { "Battle Tower screen is unavailable" }
        return checkNotNull(backend.current(context.playerId)) {
            "Battle Tower screen opened without creating a server session"
        }.toStatus(context.playerId)
    }

    override fun abandon(context: BattleApplicationRequestContext): BattleContentStatus =
        when (backend.abandon(context.playerId)) {
            TowerSessionAbandonResult.NoSession,
            TowerSessionAbandonResult.SessionClosed,
            -> available(context.playerId)

            is TowerSessionAbandonResult.ForfeitRequested ->
                checkNotNull(backend.current(context.playerId)) {
                    "Battle Tower forfeit was requested without an active server session"
                }.toStatus(context.playerId)

            is TowerSessionAbandonResult.ForfeitUnavailable ->
                error("Active Battle Tower battle could not be forfeited")
        }

    private fun TowerPlayViewState.toStatus(playerId: UUID): BattleContentStatus = BattleContentStatus(
        playerId = playerId,
        contentId = CONTENT_ID,
        formatId = format.toApplicationId(),
        phase = when (phase) {
            TowerPlayPhase.SELECTING, TowerPlayPhase.TEAM_LOCKED, TowerPlayPhase.CHANGING_TEAM -> BattleContentPhase.PREPARING
            TowerPlayPhase.ACTIVE -> BattleContentPhase.ACTIVE
        },
        progress = progressValues(playerId),
    )

    private fun available(playerId: UUID) = BattleContentStatus(
        playerId = playerId,
        contentId = CONTENT_ID,
        formatId = null,
        phase = BattleContentPhase.AVAILABLE,
        progress = progressValues(playerId),
    )

    private fun progressValues(playerId: UUID): Map<String, Long> = buildMap {
        val progressByTrack = backend.progress(playerId)
        TowerTrack.entries.forEach { track ->
            val progress = progressByTrack[track] ?: return@forEach
            // Endless keeps the plain format prefix it had before Normal existed.
            val prefix = track.recordId
            put("${prefix}_current_win_streak", progress.currentWinStreak.toLong())
            put("${prefix}_best_win_streak", progress.bestWinStreak.toLong())
            put("${prefix}_bp_per_win", TowerProgression.rewardForNextVictory(progress).toLong())
        }
    }
}

private val CONTENT_ID = BattleContentId(ManagedBattleContentIds.BATTLE_TOWER)

private fun TowerBattleFormat.toApplicationId(): BattleFormatId = BattleFormatId(name.lowercase())

private fun BattleFormatId.toTowerFormat(): TowerBattleFormat = when (value) {
    "single" -> TowerBattleFormat.SINGLE
    "double" -> TowerBattleFormat.DOUBLE
    else -> error("Unsupported Battle Tower format: $value")
}
