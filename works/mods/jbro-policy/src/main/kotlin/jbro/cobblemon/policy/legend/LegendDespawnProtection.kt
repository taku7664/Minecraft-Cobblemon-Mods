package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.api.entity.Despawner
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents

/** Keeps wild Legends available for five minutes after spawning before Cobblemon's ordinary distance rules apply. */
internal object LegendDespawnProtection {
    private const val SPAWN_TIME_KEY = "jbro_policy_legend_spawn_time"
    private const val MIN_AGE_MILLIS = 5 * 60 * 1000L

    fun register() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe { event ->
            if (!event.isCanceled) protect(event.entity)
        }
        // Also covers command spawns and Legends loaded from saved chunks.
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is PokemonEntity) protect(entity)
        }
    }

    private fun protect(entity: PokemonEntity) {
        if (!entity.pokemon.isWild() || LegendPolicy.legendOf(entity.pokemon) == null) return
        val data = entity.pokemon.persistentData
        if (!data.contains(SPAWN_TIME_KEY)) data.putLong(SPAWN_TIME_KEY, System.currentTimeMillis())
        val original = entity.despawner
        if (original is FiveMinuteDespawner) return
        entity.despawner = FiveMinuteDespawner(original)
    }

    private class FiveMinuteDespawner(private val original: Despawner<PokemonEntity>) : Despawner<PokemonEntity> {
        override fun beginTracking(entity: PokemonEntity) = original.beginTracking(entity)

        override fun shouldDespawn(entity: PokemonEntity): Boolean {
            val elapsed = System.currentTimeMillis() - entity.pokemon.persistentData.getLong(SPAWN_TIME_KEY)
            return elapsed >= MIN_AGE_MILLIS && original.shouldDespawn(entity)
        }
    }
}
