package jbro.cobblemon.policy.pokemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpawnBucketOrderTest {
    @Test
    fun `rarity chain goes most common first and world-only buckets last`() {
        val world = hashMapOf("boss" to 0.3f, "common" to 94f, "ultra-rare" to 0.2f, "rare" to 0.5f, "uncommon" to 5f)
        val chain = setOf("common", "uncommon", "rare", "ultra-rare")

        assertEquals(listOf("common", "uncommon", "rare", "ultra-rare", "boss"), SpawnBucketOrder.ordered(world, chain).keys.toList())
    }
}
