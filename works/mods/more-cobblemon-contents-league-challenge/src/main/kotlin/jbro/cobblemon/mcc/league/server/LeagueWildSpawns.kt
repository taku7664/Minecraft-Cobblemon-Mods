package jbro.cobblemon.mcc.league.server

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import dev.matthiesen.cobbled_level_control.common.config.CLCConfig
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.system.LeagueEngine
import jbro.cobblemon.mcc.league.system.WildSpawnLevel
import jbro.cobblemon.mcc.league.system.WildSpawnRegions
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Scale only player-caused, unowned Cobblemon spawns; CLC's own scaling must be disabled. The level follows the
 * league's [jbro.cobblemon.mcc.league.system.WildLevelRule] below the lesser of the fixed regional level and
 * the causing player's cap. A species that is not met that low steps back down its evolution line
 * ([WildSpawnSpecies]), and the moves are learned again for the new level.
 */
object LeagueWildSpawns {
    /** The regional level, [player]'s cap and the band a new spawn there draws from. */
    data class Reading(val regionLevel: Int, val cap: Int, val range: IntRange)

    fun register() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.NORMAL) { event ->
            val player = event.cause.entity as? ServerPlayer ?: return@subscribe
            if (event.entity.pokemon.isPlayerOwned()) return@subscribe
            val catalog = LeagueCatalogResources.current ?: return@subscribe
            if (CLCConfig.SERVER_CONFIG.scaling_enableScaling.get() || CLCConfig.getCatchingConfig().doNotRestrictCatching()) return@subscribe
            try {
                val level = event.entity.level() as? ServerLevel ?: return@subscribe
                val chunk = event.entity.chunkPosition()
                val reading = read(player, level, chunk.x, chunk.z) ?: return@subscribe
                LeagueIntegrations.syncCap(player, reading.cap)
                val pokemon = event.entity.pokemon
                val spawnLevel = WildSpawnLevel.roll(reading.cap, catalog.wildLevel, reading.regionLevel, player.level().random.nextDouble())
                WildSpawnSpecies.fit(pokemon, spawnLevel)
                pokemon.level = spawnLevel
                // Cobblemon picked the moves for the level it rolled; a level the League moved needs its own.
                pokemon.initializeMoveset()
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Could not scale League wild spawn for {}: {}", player.uuid, failure.message)
            }
        }
    }

    /** What a new wild spawn caused by [player] in chunk ([chunkX], [chunkZ]) of [level] would use; null without a League. */
    fun read(player: ServerPlayer, level: ServerLevel, chunkX: Int, chunkZ: Int): Reading? {
        val catalog = LeagueCatalogResources.current ?: return null
        val cap = LeagueEngine(catalog).cap(LeagueSavedData.get(player.server).read(catalog.id, player.uuid))
        val rule = catalog.wildLevel
        val origin = WildSpawnOrigin.get(player.server)
        val regionLevel = WildSpawnRegions.level(level.seed, level.dimension().location().toString(), chunkX, chunkZ,
            rule, origin.chunkX, origin.chunkZ)
        return Reading(regionLevel, cap, rule.range(cap, regionLevel))
    }
}
