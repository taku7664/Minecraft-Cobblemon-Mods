package jbro.cobblemon.policy.legend

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegendCatalogTest {
    private fun json(path: String) = JsonParser.parseString(checkNotNull(javaClass.getResource(path)) { "$path missing" }.readText()).asJsonObject

    private val spawned = json("/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json")
        .getAsJsonArray("spawns").map { it.asJsonObject.get("id").asString.removePrefix("jbro-legendary-") }.toSet()

    @Test
    fun `every Legend spawns in the wild`() {
        assertEquals(spawned, LegendCatalog.byId.keys)
    }

    @Test
    fun `Koraidon and Miraidon answer any paradox, and the Legend paradoxes need them`() {
        for (species in listOf("koraidon", "miraidon")) assertEquals(LegendCatalog.PARADOXES, LegendCatalog[species]!!.entry)
        assertEquals(20, LegendCatalog.PARADOXES.toSet().size)
        assertEquals(listOf("koraidon"), LegendCatalog["walkingwake"]!!.entry)
        assertEquals(listOf("miraidon"), LegendCatalog["ironcrown"]!!.entry)
    }

    @Test
    fun `a Galarian bird is its own Legend, other forms stay their species`() {
        assertEquals("zapdos-galar", LegendCatalog.of("cobblemon:zapdos", setOf("galarian"))?.id)
        assertEquals("zapdos", LegendCatalog.of("zapdos", emptySet())?.id)
        assertEquals("deoxys", LegendCatalog.of("deoxys", setOf("attack"))?.id)
        assertTrue(LegendCatalog.hasForms("moltres"))
        assertFalse(LegendCatalog.hasForms("deoxys"))
        assertEquals(null, LegendCatalog.of("meowth", setOf("galarian")))
    }

    @Test
    fun `every Legend can be reached from Pokemon that are not Legends`() {
        // A Pokemon outside the catalog is met freely; a Legend once one of its entry Pokemon (all, for entryAll) is.
        // Entry loops are fine while some way round them is free: Koraidon takes any paradox, Walking Wake Koraidon.
        val reached = mutableSetOf<String>()
        fun met(species: String) = LegendCatalog[species] == null || species in reached
        do {
            val before = reached.size
            for (legend in LegendCatalog.byId.values) {
                val open = legend.entry.isEmpty() || if (legend.entryAll) legend.entry.all(::met) else legend.entry.any(::met)
                if (open) reached += legend.id
            }
        } while (reached.size > before)
        assertEquals(LegendCatalog.byId.keys, reached)
    }

    @Test
    fun `an entry never needs a higher rank than the Legend`() {
        for (legend in LegendCatalog.byId.values) {
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
            for (legend in LegendCatalog.byId.values) {
                assertTrue("legend.jbro_policy.appeared.${legend.id}" in keys) { "$lang has no appearance line for ${legend.id}" }
                if (legend.aspect != null) assertTrue(legend.nameKey in keys) { "$lang has no name for ${legend.id}" }
            }
        }
    }
}
