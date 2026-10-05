package jbro.cobblemon.dimensions.wormhole

import jbro.cobblemon.dimensions.CobblemonDimensions
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer

/**
 * Who may pass an Ultra Wormhole: players who beat the normal League, the Elite Four and its Champion. Without the
 * League mod installed (development runs) anyone may; with it installed but no League loaded, no one may.
 */
object UltraSpaceAccess {
    private val leagueInstalled by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    fun allowed(player: ServerPlayer): Boolean {
        if (!leagueInstalled) return true
        return try {
            LeagueRanks.of(player.server, player.uuid) == LeagueRank.CHAMPION
        } catch (failure: RuntimeException) {
            CobblemonDimensions.LOGGER.warn("Could not read {}'s League rank; keeping them out of Ultra Space", player.scoreboardName, failure)
            false
        }
    }
}
