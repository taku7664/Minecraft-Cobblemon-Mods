package jbro.cobblemon.mcc.league

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.league.server.*

object MoreCobblemonContentsLeagueChallenge : ModInitializer {
    const val MOD_ID: String = "more_cobblemon_contents_league_challenge"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LeagueCatalogResources.register()
        LeagueTerminal.register()
        LeagueServer.register()
        LeagueWildSpawns.register()
        LeagueAdminCommands.register()
    }
}
