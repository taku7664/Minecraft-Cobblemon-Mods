package jbro.cobblemon.mcc.internal.record

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattleRecordNewsTest {
    private val player = UUID.randomUUID()

    private fun stats(content: String, streak: Int, floor: Long? = null) = BattleRecordStats(
        BattleRecordKey(player, BattleRecordCategory(content, "single")),
        bestWinStreak = streak,
        currentWinStreak = streak,
        bestMetrics = floor?.let { mapOf(BattleRecordMetrics.HIGHEST_FLOOR to it) }.orEmpty(),
    )

    @Test
    fun `a best streak is news at every tenth win and not in between`() {
        val tower = ManagedBattleContentIds.BATTLE_TOWER
        assertEquals(listOf(BattleRecordNews.STREAK_KIND to 10L),
            BattleRecordNews.milestones(stats(tower, 9), stats(tower, 10)))
        assertEquals(emptyList<Pair<String, Long>>(), BattleRecordNews.milestones(stats(tower, 10), stats(tower, 11)))
        assertEquals(listOf(BattleRecordNews.STREAK_KIND to 20L),
            BattleRecordNews.milestones(stats(tower, 19), stats(tower, 20)))
    }

    @Test
    fun `a best floor is news at every finished round of seven`() {
        val factory = ManagedBattleContentIds.BATTLE_FACTORY
        assertEquals(listOf(BattleRecordNews.FLOOR_KIND to 7L),
            BattleRecordNews.milestones(stats(factory, 0, 6), stats(factory, 0, 7)))
        assertEquals(emptyList<Pair<String, Long>>(), BattleRecordNews.milestones(stats(factory, 0, 7), stats(factory, 0, 8)))
    }

    @Test
    fun `beating an old best below it, and PvP, make no news`() {
        val tower = ManagedBattleContentIds.BATTLE_TOWER
        // Best stays 30 while a new run climbs back past 10: not a new best.
        assertEquals(emptyList<Pair<String, Long>>(), BattleRecordNews.milestones(stats(tower, 30), stats(tower, 30)))
        val pvp = ManagedBattleContentIds.PVP
        assertEquals(emptyList<Pair<String, Long>>(), BattleRecordNews.milestones(stats(pvp, 9), stats(pvp, 10)))
    }
}
