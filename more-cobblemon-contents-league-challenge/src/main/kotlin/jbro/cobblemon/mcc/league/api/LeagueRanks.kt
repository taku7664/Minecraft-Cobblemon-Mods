package jbro.cobblemon.mcc.league.api

import java.util.UUID
import jbro.cobblemon.mcc.league.server.LeagueCatalogResources
import jbro.cobblemon.mcc.league.server.LeagueSavedData
import jbro.cobblemon.mcc.league.server.LeagueServer
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.minecraft.server.MinecraftServer

/** Server-side read of a player's League rank for other mods. Call on the server thread. */
object LeagueRanks {
    /** Null while no League catalog is loaded. */
    fun of(server: MinecraftServer, player: UUID): LeagueRank? {
        val catalog = LeagueCatalogResources.current ?: return null
        return LeagueServer.rank(catalog, LeagueSavedData.get(server).read(catalog.id, player))
    }
}
