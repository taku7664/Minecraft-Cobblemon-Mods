package jbro.cobblemon.policy.legend

import jbro.cobblemon.policy.pokemon.SpawnBucketOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegendSpawnBucketTest {
    private val chain = setOf("common", "uncommon", "rare", "ultra-rare")

    @Test
    fun `the legend bucket joins the world buckets and lists after boss`() {
        val world = hashMapOf("boss" to 0.3f, "common" to 94f, "ultra-rare" to 0.2f, "rare" to 0.5f, "uncommon" to 5f)
        val ordered = SpawnBucketOrder.ordered(LegendSpawnBucket.withLegendBucket(world), chain)
        assertEquals(listOf("common", "uncommon", "rare", "ultra-rare", "boss", "legendary"), ordered.keys.toList())
        assertEquals(LegendSpawnBucket.WEIGHT, ordered["legendary"])
    }

    @Test
    fun `a weight set in an exported config is kept`() {
        val world = mapOf("common" to 94f, "legendary" to 0.01f)
        assertEquals(0.01f, LegendSpawnBucket.withLegendBucket(world)["legendary"])
    }
}
