package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.internal.record.BattleRecordStats
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds

internal object TowerRecordContract {
    const val CONTENT_ID = ManagedBattleContentIds.BATTLE_TOWER
}

internal object TowerProgressRecordCodec {
    fun decode(stats: BattleRecordStats): TowerProgress {
        require(stats.key.category.contentId == TowerRecordContract.CONTENT_ID) {
            "Not a Battle Tower record: ${stats.key.category.contentId}"
        }
        val track = TowerTrack.forRecordId(stats.key.category.formatId)
            ?: throw IllegalArgumentException("Unsupported Battle Tower format: ${stats.key.category.formatId}")

        return TowerProgress(
            format = track.format,
            currentWinStreak = stats.currentWinStreak,
            bestWinStreak = stats.bestWinStreak,
            mode = track.mode,
        )
    }
}
