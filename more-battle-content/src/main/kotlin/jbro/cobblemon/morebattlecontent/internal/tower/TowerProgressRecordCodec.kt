package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal object TowerRecordContract {
    const val CONTENT_ID = "battle_tower"
}

internal object TowerProgressRecordCodec {
    fun decode(stats: BattleRecordStats): TowerProgress {
        require(stats.key.category.contentId == TowerRecordContract.CONTENT_ID) {
            "Not a Battle Tower record: ${stats.key.category.contentId}"
        }
        val format = TowerBattleFormat.entries.singleOrNull {
            it.recordId == stats.key.category.formatId
        } ?: throw IllegalArgumentException("Unsupported Battle Tower format: ${stats.key.category.formatId}")

        return TowerProgress(
            format = format,
            currentWinStreak = stats.currentWinStreak,
            bestWinStreak = stats.bestWinStreak,
        )
    }
}
