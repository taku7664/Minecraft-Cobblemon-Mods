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
    fun `bundled catalog prices growth goods low and battle items at a session of battles`() {
        val loaded = BattlePointShopCatalogLoader.loadSeparated(
            fragmentReaders(RULE_DIRECTORY),
            fragmentReaders(ENTRY_DIRECTORY),
        ) { true }
        assertTrue(loaded is BattlePointShopCatalogLoadResult.Loaded)
        val catalog = (loaded as BattlePointShopCatalogLoadResult.Loaded).catalog
        fun price(entryId: String) = requireNotNull(catalog.entry(entryId)) { entryId }.priceBp

        assertEquals("mcc_core", catalog.catalogId)
        assertEquals(106, catalog.entries().size)
        assertEquals(106, catalog.entries().map { it.sortOrder }.distinct().size)
        // Levels and EVs are no burden.
        assertTrue(catalog.entries().none { it.itemId.endsWith("_candy") || it.itemId.contains(":exp_candy_") ||
            (it.itemId.endsWith("_ball") && it.itemId != "cobblemon:iron_ball") ||
            it.itemId in setOf("cobblemon:exp_share", "cobblemon:ether", "cobblemon:max_ether", "cobblemon:elixir", "cobblemon:max_elixir") })
        assertTrue(listOf("hp_up", "protein", "iron", "calcium", "zinc", "carbos").all { price(it) == 2L })
        assertEquals(6, catalog.entries().count { it.entryId.endsWith("_feather") && it.priceBp == 1L })
        // Mints are farmed later anyway.
        assertEquals(21, catalog.entries().count { it.entryId.endsWith("_mint") && it.priceBp == 10L })
        // Candies are crafted or found while exploring; BP remains for the other goods.
        // A hidden ability costs a Life Orb.
        assertEquals(30L, price("ability_capsule"))
        assertEquals(100L, price("ability_patch"))
        // Items with no recipe: the Lucky Egg and the evolution items otherwise found only in chests.
        assertEquals(50L, price("lucky_egg"))
        assertEquals("held_item", catalog.entry("lucky_egg")?.category)
        // Every evolution item but the stones costs the same.
        val evolutionItems = listOf("oval_stone", "razor_claw", "razor_fang", "dragon_scale", "prism_scale", "link_cable",
            "kings_rock", "metal_coat", "upgrade", "dubious_disc", "protector", "electirizer", "magmarizer", "reaper_cloth",
            "sachet", "whipped_dream")
        assertTrue(evolutionItems.all { price(it) == 25L && catalog.entry(it)?.category == "consumable" })
        assertEquals(listOf(10L, 25L), listOf("pp_up", "pp_max").map(::price))
        assertEquals(25L, price("ability_shield"))
        // Battle items: Life Orb at 100 sets the scale; easily crafted ones are cheaper still.
        val heldItems = catalog.entries().filter { it.category == "held_item" }
        assertEquals(24, heldItems.size)
        assertEquals(listOf(100L, 100L), listOf("life_orb", "choice_specs").map(::price))
        assertTrue(listOf("choice_band", "choice_scarf", "assault_vest").all { price(it) == 75L })
        assertEquals(6, heldItems.count { it.priceBp == 50L })
        assertEquals(5, heldItems.count { it.priceBp == 40L })
        assertEquals(7, heldItems.count { it.priceBp == 25L })
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
        // Mints and ability changes are used up, so they sit with the consumables; the Ability Shield is a held item.
        assertTrue(catalog.entries().filter { it.entryId.endsWith("_mint") || it.entryId in setOf("ability_capsule", "ability_patch") }.all { it.category == "consumable" })
        assertEquals("held_item", catalog.entry("ability_shield")?.category)
        assertEquals(listOf("firework_rocket", "enchanted_golden_apple"), catalog.entries().filter { it.category == "misc" }.map { it.entryId })
        assertEquals(2L, price("firework_rocket"))
        assertEquals(120L, price("enchanted_golden_apple"))
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
