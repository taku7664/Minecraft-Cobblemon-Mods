package jbro.cobblemon.mcc.internal.record

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.hub.MccDashboardCards
import jbro.cobblemon.mcc.api.news.MccNews
import jbro.cobblemon.mcc.api.news.MccNewsEvent
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * News when a record reaches a milestone: a personal best win streak at every tenth win, and a personal best floor
 * at every seventh, one Battle Factory round. PvP records stay out, as its results are not announced.
 */
internal object BattleRecordNews {
    const val STREAK_STEP = 10
    const val FLOOR_STEP = 7
    const val STREAK_KIND = "${MoreCobblemonContents.MOD_ID}:win_streak"
    const val FLOOR_KIND = "${MoreCobblemonContents.MOD_ID}:highest_floor"
    private const val KEY = "news.${MoreCobblemonContents.MOD_ID}"

    /** The milestones [after] reached that [before] had not. */
    fun milestones(before: BattleRecordStats, after: BattleRecordStats): List<Pair<String, Long>> = buildList {
        if (after.key.category.contentId == ManagedBattleContentIds.PVP) return@buildList
        val streak = after.bestWinStreak.toLong()
        if (crossed(before.bestWinStreak.toLong(), streak, STREAK_STEP)) add(STREAK_KIND to streak)
        val floor = after.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L
        if (crossed(before.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L, floor, FLOOR_STEP)) add(FLOOR_KIND to floor)
    }

    fun announce(server: MinecraftServer, before: BattleRecordStats, after: BattleRecordStats) {
        for ((kind, value) in milestones(before, after)) {
            val category = after.key.category
            val message = Component.translatable(
                if (kind == STREAK_KIND) "$KEY.win_streak" else "$KEY.highest_floor",
                MccNews.playerName(server, after.key.playerId),
                MccDashboardCards.contentName(category.contentId),
                MccDashboardCards.formatName(category.formatId),
                value,
            )
            MccNews.publish(server, MccNewsEvent(kind, after.key.playerId, message))
        }
    }

    private fun crossed(before: Long, after: Long, step: Int) = after > before && after / step > before / step
}
