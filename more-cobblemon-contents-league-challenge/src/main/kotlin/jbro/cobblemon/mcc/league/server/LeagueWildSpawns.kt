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
 * league's [jbro.cobblemon.mcc.league.system.WildLevelRule] under the causing player's cap, leaning weak or strong
 * by where the Pokemon appears.
 */
object LeagueWildSpawns {
    fun register() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.NORMAL) { event ->
            val player = event.cause.entity as? ServerPlayer ?: return@subscribe
            if (event.entity.pokemon.isPlayerOwned()) return@subscribe
            val catalog = LeagueCatalogResources.current ?: return@subscribe
            if (CLCConfig.SERVER_CONFIG.scaling_enableScaling.get() || CLCConfig.getCatchingConfig().doNotRestrictCatching()) return@subscribe
            try {
                val state = LeagueSavedData.get(player.server).read(catalog.id, player.uuid)
                val cap = LeagueEngine(catalog).cap(state)
                LeagueIntegrations.syncCap(player, cap)
                val level = event.entity.level() as? ServerLevel ?: return@subscribe
                val chunk = event.entity.chunkPosition()
                val rule = catalog.wildLevel
                val lean = WildSpawnRegions.lean(level.seed, level.dimension().location().toString(), chunk.x, chunk.z, rule.regionChunks)
                event.entity.pokemon.level = WildSpawnLevel.roll(cap, rule, lean, player.level().random.nextDouble())
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Could not scale League wild spawn for {}: {}", player.uuid, failure.message)
            }
        }
    }
}
