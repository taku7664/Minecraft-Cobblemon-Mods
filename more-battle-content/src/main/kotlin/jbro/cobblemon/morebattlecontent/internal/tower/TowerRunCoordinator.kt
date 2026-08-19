package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal data class TowerActiveBattle(
    val battleId: UUID,
    val opponentKind: TowerOpponentKind,
)

internal data class TowerRunSnapshot(
    val playerId: UUID,
    val progress: TowerProgress,
    val activeBattle: TowerActiveBattle? = null,
)

internal sealed interface TowerRunStartResult {
    data class Started(val run: TowerRunSnapshot) : TowerRunStartResult
    data object AlreadyActive : TowerRunStartResult
}

internal sealed interface TowerBattleStartResult {
    data class Started(val run: TowerRunSnapshot) : TowerBattleStartResult
    data object NoActiveRun : TowerBattleStartResult
    data class AlreadyInBattle(val battleId: UUID) : TowerBattleStartResult
    data class BattleIdInUse(val playerId: UUID) : TowerBattleStartResult
}

internal sealed interface TowerBattleCompletionResult {
    data class Completed(
        val run: TowerRunSnapshot,
        val progressUpdate: TowerProgressUpdate,
        val record: BattleRecordStats,
    ) : TowerBattleCompletionResult

    data object NoActiveRun : TowerBattleCompletionResult
    data object NoActiveBattle : TowerBattleCompletionResult
    data class StaleBattle(val activeBattleId: UUID) : TowerBattleCompletionResult
}

internal class TowerRunCoordinator(private val records: TowerBattleRecordService) {
    private val runs = LinkedHashMap<UUID, TowerRunSnapshot>()
    private val battleOwners = HashMap<UUID, UUID>()

    @Synchronized
    fun start(playerId: UUID, progress: TowerProgress): TowerRunStartResult {
        if (runs.containsKey(playerId)) return TowerRunStartResult.AlreadyActive
        val run = TowerRunSnapshot(playerId = playerId, progress = progress)
        runs[playerId] = run
        return TowerRunStartResult.Started(run)
    }

    @Synchronized
    fun resume(playerId: UUID): TowerRunSnapshot? = runs[playerId]

    @Synchronized
    fun beginBattle(playerId: UUID, battleId: UUID): TowerBattleStartResult {
        val current = runs[playerId] ?: return TowerBattleStartResult.NoActiveRun
        current.activeBattle?.let { return TowerBattleStartResult.AlreadyInBattle(it.battleId) }
        battleOwners[battleId]?.let { return TowerBattleStartResult.BattleIdInUse(it) }

        val activeBattle = TowerActiveBattle(
            battleId = battleId,
            opponentKind = TowerProgression.nextOpponent(current.progress),
        )
        val updated = current.copy(activeBattle = activeBattle)
        battleOwners[battleId] = playerId
        runs[playerId] = updated
        return TowerBattleStartResult.Started(updated)
    }

    @Synchronized
    fun completeBattle(
        playerId: UUID,
        battleId: UUID,
        outcome: TowerBattleOutcome,
    ): TowerBattleCompletionResult {
        val current = runs[playerId] ?: return TowerBattleCompletionResult.NoActiveRun
        val active = current.activeBattle ?: return TowerBattleCompletionResult.NoActiveBattle
        if (active.battleId != battleId) return TowerBattleCompletionResult.StaleBattle(active.battleId)

        val progressUpdate = TowerProgression.record(current.progress, outcome)
        check(progressUpdate.completedOpponent == active.opponentKind) {
            "Tower opponent changed during active battle"
        }
        val record = records.record(playerId, progressUpdate)
        val updated = current.copy(progress = progressUpdate.after, activeBattle = null)
        battleOwners.remove(battleId)
        runs[playerId] = updated
        return TowerBattleCompletionResult.Completed(updated, progressUpdate, record)
    }

    @Synchronized
    fun abandon(playerId: UUID): Boolean {
        val removed = runs.remove(playerId) ?: return false
        removed.activeBattle?.let { battleOwners.remove(it.battleId) }
        return true
    }
}
