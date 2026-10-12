package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.api.spawning.preset.BestSpawnerConfig
import jbro.cobblemon.policy.mixin.BestSpawnerConfigAccessor

/**
 * Cobblenav lists rarities in the order of the spawner config's bucket map, and Cobblemon 1.8 loads that map without a
 * fixed order. After each load the world and fishing maps are rebuilt in a stable order: the rarity chain shared with
 * habitats and Poke Snacks first, most common first, then buckets only the world spawner has (such as boss).
 * Weighted picks do not depend on iteration order, so spawn rates stay the same.
 */
object SpawnBucketOrder {
    fun apply(config: BestSpawnerConfig) {
        val chain = config.activatedHabitatBuckets.keys + config.pokeSnackBuckets.keys
        val accessor = config as BestSpawnerConfigAccessor
        accessor.`jbroPolicy$setWorldBuckets`(ordered(config.worldBuckets, chain))
        accessor.`jbroPolicy$setFishingBuckets`(ordered(config.fishingBuckets, chain))
    }

    fun ordered(buckets: Map<String, Float>, chain: Set<String>): Map<String, Float> =
        buckets.entries
            .sortedWith(compareBy<Map.Entry<String, Float>> { it.key !in chain }.thenByDescending { it.value })
            .associateTo(LinkedHashMap()) { it.key to it.value }
}
