package jbro.cobblemon.mcc.internal.bp.shop

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourceTest {
    @Test
    fun `bundled catalog prices growth goods low, IV and ability changes high and battle items highest of all`() {
        val loaded = BattlePointShopCatalogLoader.loadSeparated(
            fragmentReaders(RULE_DIRECTORY),
            fragmentReaders(ENTRY_DIRECTORY),
        ) { true }
        assertTrue(loaded is BattlePointShopCatalogLoadResult.Loaded)
        val catalog = (loaded as BattlePointShopCatalogLoadResult.Loaded).catalog
        fun price(entryId: String) = requireNotNull(catalog.entry(entryId)) { entryId }.priceBp

        assertEquals("mcc_core", catalog.catalogId)
        assertEquals(74, catalog.entries().size)
        assertEquals(74, catalog.entries().map { it.sortOrder }.distinct().size)
        // Levels and EVs are no burden.
        assertEquals(listOf(1L, 2L, 4L, 10L, 3L), listOf("exp_candy_s", "exp_candy_m", "exp_candy_l", "exp_candy_xl", "rare_candy").map(::price))
        assertTrue(listOf("hp_up", "protein", "iron", "calcium", "zinc", "carbos").all { price(it) == 2L })
        assertEquals(6, catalog.entries().count { it.entryId.endsWith("_feather") && it.priceBp == 1L })
        // Mints are farmed later anyway.
        assertEquals(21, catalog.entries().count { it.entryId.endsWith("_mint") && it.priceBp == 20L })
        // Raising an IV is a choice worth weighing; lowering one is fair.
        assertTrue(listOf("health", "mighty", "tough", "smart", "courage", "quick").all { price("${it}_candy") == 15L })
        assertTrue(listOf("sickly", "weak", "brittle", "numb", "coward", "slow").all { price("${it}_candy") == 5L })
        // A hidden ability is the dearest thing in the shop.
        assertEquals(100L, price("ability_capsule"))
        assertEquals(2500L, price("ability_patch"))
        assertEquals("ability_patch", catalog.entries().maxBy { it.priceBp }.entryId)
        // Battle items are very dear.
        assertEquals(5, catalog.entries().count { it.priceBp == 400L })
        assertEquals(5, catalog.entries().count { it.priceBp == 250L })
        assertEquals(8, catalog.entries().count { it.priceBp == 200L })
        assertEquals(4, catalog.entries().count { it.priceBp == 150L })
        assertEquals(150L, price("adrenaline_orb"))
        assertEquals("mega_showdown:adrenaline_orb", catalog.entry("adrenaline_orb")?.itemId)
    }

    private fun fragmentReaders(directory: String): List<Pair<String, Reader>> =
        resourceFiles(directory).map { path -> path.fileName.toString() to Files.newBufferedReader(path) }

    private fun resourceFiles(directory: String): List<Path> {
        val url = javaClass.getResource(directory)
        assertNotNull(url, "Missing bundled resource directory: $directory")
        return Files.list(Paths.get(url!!.toURI())).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
        }
    }

    companion object {
        const val RULE_DIRECTORY = "/data/more_cobblemon_contents/mcc-bp-shop/rules"
        const val ENTRY_DIRECTORY = "/data/more_cobblemon_contents/mcc-bp-shop/entries"
    }
}
