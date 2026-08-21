package jbro.cobblemon.morebattlecontent.internal.bp.shop

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
    fun `bundled catalog contains the approved provisional 44 item price bands`() {
        val loaded = BattlePointShopCatalogLoader.loadSeparated(
            fragmentReaders(RULE_DIRECTORY),
            fragmentReaders(ENTRY_DIRECTORY),
        ) { true }
        assertTrue(loaded is BattlePointShopCatalogLoadResult.Loaded)
        val catalog = (loaded as BattlePointShopCatalogLoadResult.Loaded).catalog

        assertEquals("mbc_core", catalog.catalogId)
        assertEquals(44, catalog.entries().size)
        assertEquals(21, catalog.entries().count { it.entryId.endsWith("_mint") })
        assertEquals(22, catalog.entries().count { it.priceBp == 50L })
        assertEquals(5, catalog.entries().count { it.priceBp == 25L })
        assertEquals(5, catalog.entries().count { it.priceBp == 20L })
        assertEquals(8, catalog.entries().count { it.priceBp == 15L })
        assertEquals(4, catalog.entries().count { it.priceBp == 10L })
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
        const val RULE_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-bp-shop/rules"
        const val ENTRY_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-bp-shop/entries"
    }
}
