package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.io.InputStreamReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourceTest {
    @Test
    fun `bundled catalog contains the approved provisional 44 item price bands`() {
        val stream = javaClass.getResourceAsStream(CATALOG_PATH)
        assertNotNull(stream)
        val loaded = InputStreamReader(stream!!).use { reader ->
            BattlePointShopCatalogLoader.load(reader) { true }
        }
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

    companion object {
        const val CATALOG_PATH = "/data/cobblemon_more_battle_content/bp_shop/catalog/mbc_core.json"
    }
}
