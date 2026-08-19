package jbro.cobblemon.morebattlecontent.internal.tower.application

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.application.BattleApplicationRequestContext
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentApplication
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentDescriptor
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentId
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentPhase
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentStatus
import jbro.cobblemon.morebattlecontent.internal.application.BattleFormatId
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerSessionAbandonResult

internal interface BattleTowerApplicationBackend {
    fun current(playerId: UUID): TowerPlayViewState?

    fun progress(playerId: UUID): Map<TowerBattleFormat, TowerProgress>

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
            TowerPlayPhase.SELECTING, TowerPlayPhase.TEAM_LOCKED -> BattleContentPhase.PREPARING
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
        val progressByFormat = backend.progress(playerId)
        TowerBattleFormat.entries.forEach { format ->
            val progress = progressByFormat[format] ?: return@forEach
            val prefix = format.name.lowercase()
            put("${prefix}_rank_order", progress.rank.leaderboardOrder)
            put("${prefix}_rank_points", progress.rankPoints.toLong())
            put("${prefix}_wins_required", progress.displayWinsRequired.toLong())
            put("${prefix}_master_cycle_wins", progress.masterCycleWins.toLong())
        }
    }
}

private val CONTENT_ID = BattleContentId("battle_tower")

private fun TowerBattleFormat.toApplicationId(): BattleFormatId = BattleFormatId(name.lowercase())

private fun BattleFormatId.toTowerFormat(): TowerBattleFormat = when (value) {
    "single" -> TowerBattleFormat.SINGLE
    "double" -> TowerBattleFormat.DOUBLE
    else -> error("Unsupported Battle Tower format: $value")
}
