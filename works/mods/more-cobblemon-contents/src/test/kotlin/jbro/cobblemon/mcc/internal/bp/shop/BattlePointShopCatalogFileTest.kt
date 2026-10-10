package jbro.cobblemon.mcc.internal.bp.shop

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BattlePointShopCatalogFileTest {
    @TempDir lateinit var temp: Path
    private fun defaults() = CatalogResourceInput("default.json") { StringReader(json) }

    @Test
    fun `removing entries in the single file persists across reload and restart`() {
        val path = temp.resolve("bp-shop.json")
        val file = BattlePointShopCatalogFile(path)
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        assertTrue(reloader.reload(file.load { defaults() }) is BattlePointShopCatalogReloadOutcome.Applied)
        val edited = JsonParser.parseString(Files.readString(path)).asJsonObject
        edited.add("entries", JsonArray())
        Files.writeString(path, edited.toString())
        val restarted = BattlePointShopCatalogFile(path)
        assertTrue(reloader.reload(restarted.load { error("No fallback to defaults") }) is BattlePointShopCatalogReloadOutcome.Applied)
        assertTrue(store.snapshot()!!.entries().isEmpty())
        assertTrue(store.snapshot()!!.categories.isEmpty())
        assertEquals(1, Files.list(temp).use { it.count() })
    }

    @Test
    fun `price and shopkeeper are read from the single external file`() {
        val path = temp.resolve("bp-shop.json")
        val file = BattlePointShopCatalogFile(path)
        file.load { defaults() }
        Files.writeString(path, Files.readString(path).replace("120", "150"))
        val store = BattlePointShopCatalogStore { true }
        BattlePointShopCatalogResourceReloader(store).reload(file.load { error("No fallback to defaults") })
        assertEquals(150L, store.snapshot()!!.entry("apple")!!.priceBp)
        assertEquals(listOf(BattlePointShopkeeperAppearance.Villager("cobblemon:nurse_joy", "minecraft:desert")), store.snapshot()!!.shopkeeper)
    }

    @Test
    fun `malformed edit preserves last valid catalog without overwriting the external file`() {
        val path = temp.resolve("bp-shop.json")
        val file = BattlePointShopCatalogFile(path)
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        reloader.reload(file.load { defaults() })
        val before = store.snapshot()
        Files.writeString(path, "{")
        assertTrue(reloader.reload(file.load { error("No fallback to defaults") }) is BattlePointShopCatalogReloadOutcome.Rejected)
        assertSame(before, store.snapshot())
        assertEquals("{", Files.readString(path))
    }

    @Test
    fun `failed export never publishes partial JSON`() {
        val path = temp.resolve("bp-shop.json")
        val file = BattlePointShopCatalogFile(path)
        assertThrows(java.io.IOException::class.java) {
            file.load { CatalogResourceInput("default.json") { throw java.io.IOException("read failed") } }
        }
        assertFalse(Files.exists(path))
        assertEquals(0, Files.list(temp).use { it.count() })
        assertNotNull(file.load { defaults() })
    }

    companion object {
        val json = """{"schema_version":1,"catalog_id":"mcc_core","limits":{"max_cart_lines":16,"max_quantity_per_line":64,"max_total_items":64},"categories":["misc"],"shopkeeper":{"appearances":[{"villager":{"profession":"cobblemon:nurse_joy","type":"minecraft:desert"}}]},"entries":[{"entry_id":"apple","item_id":"minecraft:enchanted_golden_apple","item_count":1,"price_bp":120,"sort_order":1,"category":"misc"}]}"""
    }
}
