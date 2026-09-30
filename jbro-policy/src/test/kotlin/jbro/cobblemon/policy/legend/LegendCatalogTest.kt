package jbro.cobblemon.policy.legend

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegendCatalogTest {
    private fun json(path: String) = JsonParser.parseString(checkNotNull(javaClass.getResource(path)) { "$path missing" }.readText()).asJsonObject

    private val spawned = json("/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json")
        .getAsJsonArray("spawns").map { it.asJsonObject.get("pokemon").asString }.toSet()

    @Test
    fun `every spawned Legend is in the catalog and nothing else`() {
        assertEquals(spawned, LegendCatalog.bySpecies.keys)
    }

    @Test
    fun `entry Pokemon can be met without the Legend that needs them`() {
        for (legend in LegendCatalog.bySpecies.values) {
            val seen = mutableSetOf(legend.species)
            val queue = ArrayDeque(legend.entry)
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                assertFalse(next == legend.species) { "${legend.species} needs itself through its entry chain" }
                if (seen.add(next)) LegendCatalog[next]?.let { queue.addAll(it.entry) }
            }
        }
    }

    @Test
    fun `an entry never needs a higher rank than the Legend`() {
        for (legend in LegendCatalog.bySpecies.values) {
            val entryRanks = legend.entry.mapNotNull { LegendCatalog[it]?.rank }
            if (entryRanks.isEmpty()) continue
            val needed = if (legend.entryAll) entryRanks.max() else entryRanks.min()
            assertTrue(needed <= legend.rank) { "${legend.species} needs a $needed entry but is only ${legend.rank}" }
        }
    }

    @Test
    fun `decided ranks hold`() {
        assertEquals(LegendRank.CHAMPION, LegendCatalog["arceus"]!!.rank)
        assertEquals(LegendRank.CHAMPION, LegendCatalog["cobblemon:cosmog"]!!.rank)
        assertTrue(LegendCatalog["regigigas"]!!.entryAll)
        assertEquals(LegendRank.MASTER_BALL, LegendCatalog["mew"]!!.rank)
        assertEquals(LegendRank.ULTRA_BALL, LegendCatalog["articuno"]!!.rank)
    }

    @Test
    fun `entry conditions`() {
        val regigigas = LegendCatalog["regigigas"]!!
        assertFalse(regigigas.entryMet(setOf("regirock", "regice")))
        assertTrue(regigigas.entryMet(setOf("regirock", "regice", "registeel", "pikachu")))
        val lugia = LegendCatalog["lugia"]!!
        assertTrue(lugia.entryMet(setOf("zapdos")))
        assertFalse(lugia.entryMet(setOf("pikachu")))
        assertTrue(LegendCatalog["mew"]!!.entryMet(emptySet()))
    }

    @Test
    fun `every Legend has an appearance line in both languages`() {
        for (lang in listOf("ko_kr", "en_us")) {
            val keys = json("/assets/jbro_policy/lang/$lang.json").keySet()
            for (species in LegendCatalog.bySpecies.keys) {
                assertTrue("legend.jbro_policy.appeared.$species" in keys) { "$lang has no appearance line for $species" }
            }
        }
    }
}
