package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.Cobblemon
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import jbro.cobblemon.mcc.api.wiki.WikiPlayerData
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.fabricmc.loader.api.FabricLoader

/**
 * The server wiki's `legends` section of `/api/me`: the Legends a player caught themselves, their League rank and the
 * ranks it lets them catch, and their party species while they are online, so the Legend guide can mark each entry.
 * Loaded only when More Cobblemon Contents is installed.
 */
internal object LegendWikiSection {
    private val names = mapOf(
        LeagueRank.POKE_BALL to "몬스터볼", LeagueRank.GREAT_BALL to "수퍼볼", LeagueRank.ULTRA_BALL to "하이퍼볼",
        LeagueRank.MASTER_BALL to "마스터볼", LeagueRank.CHAMPION to "챔피언",
    )
    private val legendRankNames = mapOf(LegendRank.ULTRA_BALL to "하이퍼볼", LegendRank.MASTER_BALL to "마스터볼", LegendRank.CHAMPION to "챔피언")

    fun register() {
        val leagueLoaded = FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge")
        WikiPlayerData.register("legends") { server, playerId ->
            val rank = if (leagueLoaded) runCatching { LeagueRanks.of(server, playerId) }.getOrNull() else null
            // Without League Challenge nothing is gated, as at capture time.
            val allowed = LegendRank.entries.filter { needed -> !leagueLoaded || (rank != null && rank.ordinal >= LeagueRank.valueOf(needed.name).ordinal) }
            val online = server.playerList.getPlayer(playerId)
            JsonObject().apply {
                add("caught", JsonArray().also { list -> LegendRecords.get(server).caughtBy(playerId).sorted().forEach(list::add) })
                if (rank != null) addProperty("rank", names.getValue(rank))
                add("allowed", JsonArray().also { list -> allowed.forEach { list.add(legendRankNames.getValue(it)) } })
                addProperty("partyKnown", online != null)
                add("party", JsonArray().also { list ->
                    online?.let { Cobblemon.storage.getParty(it) }?.forEach { list.add(it.species.resourceIdentifier.path) }
                })
            }
        }
    }
}
