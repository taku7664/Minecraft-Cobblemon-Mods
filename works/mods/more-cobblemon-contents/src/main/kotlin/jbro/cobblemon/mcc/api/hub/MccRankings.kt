package jbro.cobblemon.mcc.api.hub

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.bp.shop.HomeLeaderboard
import jbro.cobblemon.mcc.internal.bp.shop.HomeLeaderboardRanking
import jbro.cobblemon.mcc.internal.bp.shop.homeLeaderboardBoardSpecs
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** One place on a ranking board; [value] is what the board ranks by, named by its board's [MccRankingBoard.valueName]. */
data class MccRankingEntry(val place: Int, val playerId: UUID, val playerName: String, val value: Long)

/** One ranking board of the hub: a content and format, best first. */
data class MccRankingBoard(
    val contentId: String,
    val formatId: String,
    val valueName: Component,
    val entries: List<MccRankingEntry>,
)

/**
 * The hub's ranking boards, the same ones its home screen shows: Battle Tower by best win streak, Battle Factory by
 * highest floor, PvP by wins. Read on the server thread.
 */
object MccRankings {
    private const val PREFIX = "screen.${MoreCobblemonContents.MOD_ID}.ranking"

    fun boards(server: MinecraftServer, contentId: String? = null): List<MccRankingBoard> {
        if (!BattleRecordService.isAvailable(server)) return emptyList()
        val online = server.playerList.players.associate { it.uuid to it.gameProfile.name }
        fun name(playerId: UUID): String? = online[playerId] ?: server.profileCache?.get(playerId)?.orElse(null)?.name
        return homeLeaderboardBoardSpecs().filter { contentId == null || it.contentId == contentId }.map { spec ->
            val entries = HomeLeaderboard.project(
                BattleRecordService.all(server, BattleRecordCategory(spec.contentId, spec.formatId)), spec.ranking, ::name,
            ).map { entry ->
                MccRankingEntry(entry.place, entry.playerId, entry.playerName, when (spec.ranking) {
                    HomeLeaderboardRanking.TOWER -> entry.bestWinStreak.toLong()
                    HomeLeaderboardRanking.FACTORY -> entry.highestFloor
                    HomeLeaderboardRanking.PVP -> entry.totalWins
                })
            }
            val valueName = when (spec.ranking) {
                HomeLeaderboardRanking.TOWER -> "best_streak"
                HomeLeaderboardRanking.FACTORY -> "highest_floor"
                HomeLeaderboardRanking.PVP -> "wins"
            }
            MccRankingBoard(spec.contentId, spec.formatId, Component.translatable("$PREFIX.$valueName"), entries)
        }
    }
}
