package jbro.cobblemon.mcc.league.server

import java.util.UUID
import jbro.cobblemon.mcc.league.system.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal object LeagueProgressQueries {
    fun states(server: MinecraftServer, player: UUID): Map<String, LeagueProgress> =
        LeagueCatalogResources.catalogs.keys.associateWith { LeagueSavedData.get(server).read(it, player) }

    fun earnedCap(server: MinecraftServer, player: UUID): Int =
        LeagueWorldRules.earnedCap(LeagueCatalogResources.catalogs.values, states(server, player))

    fun cap(player: ServerPlayer, catching: Boolean = false): Int =
        maxOf(earnedCap(player.server, player.uuid), LeagueIntegrations.currentCap(player, catching))
}
