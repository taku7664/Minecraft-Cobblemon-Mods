package jbro.cobblemon.mcc.pwt.server

import java.util.UUID
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer

/** PWT opens to League Champions. Without League installed there is no title to hold, so it stays open. */
internal object PwtLeagueGate {
    private const val LEAGUE = "more_cobblemon_contents_league_challenge"

    fun unlocked(server: MinecraftServer, player: UUID): Boolean =
        !FabricLoader.getInstance().isModLoaded(LEAGUE) || Champion.of(server, player)

    /** Kept apart so that League's classes load only when League is installed. */
    private object Champion {
        fun of(server: MinecraftServer, player: UUID): Boolean = LeagueRanks.of(server, player) == LeagueRank.CHAMPION
    }
}
