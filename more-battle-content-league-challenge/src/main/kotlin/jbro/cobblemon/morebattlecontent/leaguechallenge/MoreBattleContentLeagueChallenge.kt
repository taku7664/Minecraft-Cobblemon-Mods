package jbro.cobblemon.morebattlecontent.leaguechallenge

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory
import jbro.cobblemon.morebattlecontent.leaguechallenge.server.*

object MoreBattleContentLeagueChallenge : ModInitializer {
    const val MOD_ID: String = "cobblemon_more_battle_content_league_challenge"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LeagueCatalogResources.register()
        LeagueTerminal.register()
        LeagueServer.register()
        LeagueWildSpawns.register()
        LeagueAdminCommands.register()
    }
}
