package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import dev.matthiesen.cobbled_level_control.common.config.CLCConfig
import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge as Mod
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.LeagueEngine
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.WildSpawnLevel
import net.minecraft.server.level.ServerPlayer

/** Scale only player-caused, unowned Cobblemon spawns; CLC's own scaling must be disabled. */
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
                event.entity.pokemon.level = WildSpawnLevel.fromReduction(cap, player.level().random.nextInt(11))
            } catch (failure: RuntimeException) {
                Mod.LOGGER.warn("Could not scale League wild spawn for {}: {}", player.uuid, failure.message)
            }
        }
    }
}
