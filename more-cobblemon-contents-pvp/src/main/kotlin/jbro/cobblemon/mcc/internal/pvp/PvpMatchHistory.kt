package jbro.cobblemon.mcc.internal.pvp

import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

/**
 * The world's PvP match history while a server runs: opened with the world, written off the server thread so a
 * slow disk never stalls a tick, and closed after the last pending write when the server stops.
 */
internal object PvpMatchHistory {
    @Volatile
    var store: PvpMatchStore? = null
        private set
    private var writer: ExecutorService? = null

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            store = try {
                PvpMatchStore(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("mcc_pvp_matches.sqlite"))
            } catch (failure: Exception) {
                MoreCobblemonContents.LOGGER.error("PvP match history could not be opened; matches go unrecorded", failure)
                null
            }
            writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mcc-pvp-history").apply { isDaemon = true } }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            writer?.let { pool ->
                pool.shutdown()
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) MoreCobblemonContents.LOGGER.warn("PvP match history writes did not finish")
            }
            writer = null
            store = null
        }
    }

    /** Records one finished battle; names are read now, on the server thread, and the write happens later. */
    fun record(server: MinecraftServer, battleId: UUID, winnerId: UUID, loserId: UUID, format: String) {
        val history = store ?: return
        val winnerName = MccAdminArguments.name(server, winnerId)
        val loserName = MccAdminArguments.name(server, loserId)
        val playedAt = System.currentTimeMillis()
        writer?.execute {
            try {
                history.record(battleId, playedAt, format, winnerId, winnerName, loserId, loserName)
            } catch (failure: Exception) {
                MoreCobblemonContents.LOGGER.error("PvP match {} could not be recorded", battleId, failure)
            }
        }
    }
}
