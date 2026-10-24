package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.api.spawning.preset.BestSpawnerConfig
import jbro.cobblemon.policy.mixin.BestSpawnerConfigAccessor

/**
 * Legends spawn from a world bucket of their own, so their chance is a fixed share of spawn attempts wherever one can
 * appear. In ultra-rare they competed with that spot's other ultra-rare candidates, and where there were none (the End)
 * every ultra-rare roll became a legend. Cobblemon reads the bucket weights from its own jar, not from data packs, so
 * the bucket is added after each config load. A weight already in an exported config is kept.
 */
object LegendSpawnBucket {
    const val NAME = "legendary"

    /** Out of the world buckets' total of about 100, so roughly one spawn attempt in 50,000. */
    const val WEIGHT = 0.002f

    fun apply(config: BestSpawnerConfig) {
        (config as BestSpawnerConfigAccessor).`jbroPolicy$setWorldBuckets`(withLegendBucket(config.worldBuckets))
    }

    fun withLegendBucket(buckets: Map<String, Float>): Map<String, Float> =
        if (NAME in buckets) buckets else LinkedHashMap(buckets).apply { put(NAME, WEIGHT) }
}
