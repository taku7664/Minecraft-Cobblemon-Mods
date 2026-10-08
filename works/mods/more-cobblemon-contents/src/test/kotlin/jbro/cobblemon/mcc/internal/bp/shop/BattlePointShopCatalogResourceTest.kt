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
    fun `bundled catalog prices growth goods low, battle items at a session of battles and a hidden ability highest of all`() {
        val loaded = BattlePointShopCatalogLoader.loadSeparated(
            fragmentReaders(RULE_DIRECTORY),
            fragmentReaders(ENTRY_DIRECTORY),
        ) { true }
        assertTrue(loaded is BattlePointShopCatalogLoadResult.Loaded)
        val catalog = (loaded as BattlePointShopCatalogLoadResult.Loaded).catalog
        fun price(entryId: String) = requireNotNull(catalog.entry(entryId)) { entryId }.priceBp

        assertEquals("mcc_core", catalog.catalogId)
        assertEquals(102, catalog.entries().size)
        assertEquals(102, catalog.entries().map { it.sortOrder }.distinct().size)
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
        // Battle items: Life Orb at 100 sets the scale; easily crafted ones are cheaper still.
        val heldItems = catalog.entries().filter { it.category == "held_item" }
        assertEquals(22, heldItems.size)
        assertEquals(listOf(100L, 100L), listOf("life_orb", "choice_specs").map(::price))
        assertTrue(listOf("choice_band", "choice_scarf", "assault_vest").all { price(it) == 75L })
        assertEquals(5, heldItems.count { it.priceBp == 50L })
        assertEquals(5, heldItems.count { it.priceBp == 40L })
        assertEquals(6, heldItems.count { it.priceBp == 25L })
        assertEquals(5L, price("cell_battery"))
        assertEquals(25L, price("adrenaline_orb"))
        assertEquals("mega_showdown:adrenaline_orb", catalog.entry("adrenaline_orb")?.itemId)
        // Every gimmick's key item and core material, so exploring is not the only way in.
        val gimmicks = catalog.entries().filter { it.category == "gimmick" }
        assertEquals(27, gimmicks.size)
        assertTrue(gimmicks.all { it.itemId.startsWith("mega_showdown:") })
        assertEquals(listOf(150L, 100L, 150L, 50L, 50L, 150L, 50L, 25L),
            listOf("mega_bracelet", "mega_stone", "z_ring", "blank_z", "tera_orb", "dynamax_band", "wishing_star", "max_mushroom").map(::price))
        assertEquals(18, gimmicks.count { it.entryId.endsWith("_tera_shard") && it.priceBp == 2L })
        assertEquals(5L, price("stellar_tera_shard"))
        // Mints and ability changes are used up, so they sit with the consumables; fireworks are the only other goods.
        assertTrue(catalog.entries().filter { it.entryId.endsWith("_mint") || it.entryId.startsWith("ability_") }.all { it.category == "consumable" })
        assertEquals(listOf("firework_rocket"), catalog.entries().filter { it.category == "misc" }.map { it.entryId })
        assertEquals(2L, price("firework_rocket"))
        assertEquals(listOf("held_item", "gimmick", "consumable", "misc"), catalog.categories)
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
