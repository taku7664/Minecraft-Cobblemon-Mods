package jbro.cobblemon.dimensions

import java.util.UUID
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Who may enter the dimensions, by Ultra Wormhole or by portal: players who beat the normal League, the Elite Four and
 * its Champion. Without the League mod installed (development runs) anyone may; with it installed but no League
 * loaded, no one may. Lighting a portal and going home are never stopped.
 */
object DimensionAccess {
    private val leagueInstalled by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    /** When each player was last told they may not enter, so standing in a hole or a portal does not flood their chat. */
    private val refusedAt = mutableMapOf<UUID, Long>()

    fun allowed(player: ServerPlayer): Boolean {
        if (!leagueInstalled) return true
        return try {
            LeagueRanks.of(player.server, player.uuid) == LeagueRank.CHAMPION
        } catch (failure: RuntimeException) {
            CobblemonDimensions.LOGGER.warn("Could not read {}'s League rank; keeping them out", player.scoreboardName, failure)
            false
        }
    }

    /** Tells [player] in red that they may not enter yet, at most every five seconds. */
    fun refuse(player: ServerPlayer) {
        val now = player.server.tickCount.toLong()
        if (now - (refusedAt[player.uuid] ?: Long.MIN_VALUE / 2) < 100) return
        refusedAt[player.uuid] = now
        player.displayClientMessage(Component.translatable("message.cobblemon_dimensions.not_worthy").withStyle(ChatFormatting.RED), false)
    }
}
