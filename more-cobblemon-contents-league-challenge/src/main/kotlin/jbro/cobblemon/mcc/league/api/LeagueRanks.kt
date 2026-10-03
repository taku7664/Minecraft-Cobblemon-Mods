package jbro.cobblemon.mcc.league.api

import java.util.UUID
import jbro.cobblemon.mcc.league.server.LeagueCatalogResources
import jbro.cobblemon.mcc.league.server.LeagueSavedData
import jbro.cobblemon.mcc.league.server.LeagueServer
import jbro.cobblemon.mcc.league.system.LeagueEngine
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.minecraft.server.MinecraftServer

/** Server-side read of a player's League rank for other mods. Call on the server thread. */
object LeagueRanks {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MinecraftServer, UUID, LeagueRank) -> Unit>()

    /** Hears every change of a player's rank in the current League, promotions and operator edits alike, on the server thread. */
    fun onChange(listener: (MinecraftServer, UUID, LeagueRank) -> Unit) {
        listeners += listener
    }

    internal fun changed(server: MinecraftServer, player: UUID, rank: LeagueRank) {
        listeners.forEach { listener ->
            try {
                listener(server, player, rank)
            } catch (failure: RuntimeException) {
                jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge.LOGGER.warn("League rank listener failed", failure)
            }
        }
    }

    /** Null while no League catalog is loaded. */
    fun of(server: MinecraftServer, player: UUID): LeagueRank? {
        val catalog = LeagueCatalogResources.current ?: return null
        return LeagueServer.rank(catalog, LeagueSavedData.get(server).read(catalog.id, player))
    }

    /** The level cap [player] has unlocked in the current League; null while no League catalog is loaded. */
    fun levelCap(server: MinecraftServer, player: UUID): Int? {
        val catalog = LeagueCatalogResources.current ?: return null
        return LeagueEngine(catalog).cap(LeagueSavedData.get(server).read(catalog.id, player))
    }

    /** Whether [player] beat the hard League's Champion; false while no League catalog is loaded. */
    fun isHardChampion(server: MinecraftServer, player: UUID): Boolean {
        val catalog = LeagueCatalogResources.current ?: return false
        return LeagueSavedData.get(server).read(catalog.id, player).hardChampion
    }
}
