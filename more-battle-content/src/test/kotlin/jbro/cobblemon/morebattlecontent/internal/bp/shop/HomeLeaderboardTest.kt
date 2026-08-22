package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeLeaderboardTest {
    @Test
    fun `tower ranking orders best streak total wins and fewer losses with shared places`() {
        val category = BattleRecordCategory("battle_tower", "single")
        val alpha = UUID(0, 1)
        val beta = UUID(0, 2)
        val gamma = UUID(0, 3)
        val delta = UUID(0, 4)
        val records = listOf(
            stats(gamma, category, bestStreak = 9, wins = 90, losses = 10),
            stats(delta, category, bestStreak = 10, wins = 30, losses = 2),
            stats(beta, category, bestStreak = 10, wins = 40, losses = 4),
            stats(alpha, category, bestStreak = 10, wins = 40, losses = 4),
        )
        val names = mapOf(alpha to "Alpha", beta to "beta", gamma to "Gamma", delta to "Delta")

        val result = HomeLeaderboard.project(records, names::get)

        assertEquals(listOf(alpha, beta, delta, gamma), result.map(HomeLeaderboardEntry::playerId))
        assertEquals(listOf(1, 1, 3, 4), result.map(HomeLeaderboardEntry::place))
        assertEquals(listOf(10, 10, 10, 9), result.map(HomeLeaderboardEntry::bestWinStreak))
    }

    @Test
    fun `missing profile name falls back to short uuid`() {
        val category = BattleRecordCategory("battle_tower", "double")
        val playerId = UUID.fromString("12345678-0000-0000-0000-000000000001")

        val result = HomeLeaderboard.project(listOf(stats(playerId, category, 1, 1, 0))) { null }

        assertEquals("12345678", result.single().playerName)
    }

    @Test
    fun `factory ranking orders best floor wins streak and losses`() {
        val category = BattleRecordCategory("battle_factory", "single_level_50")
        val alpha = UUID(0, 11)
        val beta = UUID(0, 12)
        val gamma = UUID(0, 13)
        val records = listOf(
            genericStats(alpha, category, highestFloor = 7, wins = 20, losses = 4, bestStreak = 7),
            genericStats(beta, category, highestFloor = 8, wins = 12, losses = 3, bestStreak = 8),
            genericStats(gamma, category, highestFloor = 8, wins = 12, losses = 2, bestStreak = 8),
        )

        val result = HomeLeaderboard.project(records, HomeLeaderboardRanking.FACTORY) { it.toString() }

        assertEquals(listOf(gamma, beta, alpha), result.map(HomeLeaderboardEntry::playerId))
        assertEquals(listOf(1, 2, 3), result.map(HomeLeaderboardEntry::place))
        assertEquals(listOf(8L, 8L, 7L), result.map(HomeLeaderboardEntry::highestFloor))
    }

    @Test
    fun `pvp ranking orders wins streak and fewer losses with shared places`() {
        val category = BattleRecordCategory("pvp", "single")
        val alpha = UUID(0, 21)
        val beta = UUID(0, 22)
        val gamma = UUID(0, 23)
        val records = listOf(
            genericStats(alpha, category, wins = 14, losses = 4, bestStreak = 5),
            genericStats(beta, category, wins = 14, losses = 4, bestStreak = 5),
            genericStats(gamma, category, wins = 14, losses = 7, bestStreak = 4),
        )

        val result = HomeLeaderboard.project(records, HomeLeaderboardRanking.PVP) { it.toString() }

        assertEquals(listOf(alpha, beta, gamma), result.map(HomeLeaderboardEntry::playerId))
        assertEquals(listOf(1, 1, 3), result.map(HomeLeaderboardEntry::place))
    }

    @Test
    fun `home leaderboard catalog covers every implemented record board`() {
        val specs = homeLeaderboardBoardSpecs()

        assertEquals(8, specs.size)
        assertEquals(mapOf("battle_tower" to 2, "battle_factory" to 4, "pvp" to 2), specs.groupingBy { it.contentId }.eachCount())
        assertEquals(specs.size, specs.map { it.contentId to it.formatId }.distinct().size)
    }

    private fun stats(
        playerId: UUID,
        category: BattleRecordCategory,
        bestStreak: Int,
        wins: Long,
        losses: Long,
    ) = BattleRecordStats(
        key = BattleRecordKey(playerId, category),
        totalWins = wins,
        totalLosses = losses,
        bestWinStreak = bestStreak,
    )

    private fun genericStats(
        playerId: UUID,
        category: BattleRecordCategory,
        highestFloor: Long = 0,
        wins: Long,
        losses: Long,
        bestStreak: Int,
    ) = BattleRecordStats(
        key = BattleRecordKey(playerId, category),
        totalWins = wins,
        totalLosses = losses,
        currentWinStreak = 0,
        bestWinStreak = bestStreak,
        bestMetrics = mapOf(BattleRecordMetrics.HIGHEST_FLOOR to highestFloor),
    )
}
