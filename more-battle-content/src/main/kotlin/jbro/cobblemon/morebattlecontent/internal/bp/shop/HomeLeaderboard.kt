package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal data class HomeLeaderboardEntry(
    val place: Int,
    val playerId: UUID,
    val playerName: String,
    val highestRank: Long = 0,
    val rankProgress: Long = 0,
    val totalWins: Long = 0,
    val highestFloor: Long = 0,
    val totalLosses: Long = 0,
    val bestWinStreak: Int = 0,
)

internal enum class HomeLeaderboardRanking { TOWER, FACTORY, PVP }

internal object HomeLeaderboard {
    const val MAX_ENTRIES = 200

    fun project(
        records: List<BattleRecordStats>,
        resolveName: (UUID) -> String?,
    ): List<HomeLeaderboardEntry> = project(records, HomeLeaderboardRanking.TOWER, resolveName)

    fun project(
        records: List<BattleRecordStats>,
        ranking: HomeLeaderboardRanking,
        resolveName: (UUID) -> String?,
    ): List<HomeLeaderboardEntry> {
        val ranked = records.map { record ->
            UnrankedEntry(
                playerId = record.key.playerId,
                playerName = resolveName(record.key.playerId)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: record.key.playerId.toString().take(8),
                highestRank = record.bestMetrics[BattleRecordMetrics.HIGHEST_RANK] ?: 0L,
                rankProgress = record.progressMetrics[BattleRecordMetrics.RANK_PROGRESS] ?: 0L,
                totalWins = record.totalWins,
                highestFloor = record.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L,
                totalLosses = record.totalLosses,
                bestWinStreak = record.bestWinStreak,
            )
        }.sortedWith(scoreComparator(ranking)
                .thenBy { it.playerName.lowercase(Locale.ROOT) }
                .thenBy { it.playerId })
            .take(MAX_ENTRIES)

        var previous: UnrankedEntry? = null
        var place = 0
        return ranked.mapIndexed { index, entry ->
            if (previous?.sameScore(entry, ranking) != true) place = index + 1
            previous = entry
            HomeLeaderboardEntry(
                place,
                entry.playerId,
                entry.playerName,
                entry.highestRank,
                entry.rankProgress,
                entry.totalWins,
                entry.highestFloor,
                entry.totalLosses,
                entry.bestWinStreak,
            )
        }
    }

    private fun scoreComparator(ranking: HomeLeaderboardRanking): Comparator<UnrankedEntry> = when (ranking) {
        HomeLeaderboardRanking.TOWER -> compareByDescending<UnrankedEntry> { it.highestRank }
            .thenByDescending { it.rankProgress }
            .thenByDescending { it.totalWins }
        HomeLeaderboardRanking.FACTORY -> compareByDescending<UnrankedEntry> { it.highestFloor }
            .thenByDescending { it.totalWins }
            .thenByDescending { it.bestWinStreak }
            .thenBy { it.totalLosses }
        HomeLeaderboardRanking.PVP -> compareByDescending<UnrankedEntry> { it.totalWins }
            .thenByDescending { it.bestWinStreak }
            .thenBy { it.totalLosses }
    }

    private data class UnrankedEntry(
        val playerId: UUID,
        val playerName: String,
        val highestRank: Long,
        val rankProgress: Long,
        val totalWins: Long,
        val highestFloor: Long,
        val totalLosses: Long,
        val bestWinStreak: Int,
    ) {
        fun sameScore(other: UnrankedEntry, ranking: HomeLeaderboardRanking): Boolean = when (ranking) {
            HomeLeaderboardRanking.TOWER ->
                highestRank == other.highestRank && rankProgress == other.rankProgress && totalWins == other.totalWins
            HomeLeaderboardRanking.FACTORY ->
                highestFloor == other.highestFloor && totalWins == other.totalWins &&
                    bestWinStreak == other.bestWinStreak && totalLosses == other.totalLosses
            HomeLeaderboardRanking.PVP ->
                totalWins == other.totalWins && bestWinStreak == other.bestWinStreak && totalLosses == other.totalLosses
        }
    }
}
