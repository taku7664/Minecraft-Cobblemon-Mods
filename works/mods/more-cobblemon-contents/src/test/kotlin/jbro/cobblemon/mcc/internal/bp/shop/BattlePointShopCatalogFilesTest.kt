package jbro.cobblemon.mcc.internal.bp.shop

import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BattlePointShopCatalogFilesTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `deleting an item file removes it and reload never restores defaults`() {
        val files = BattlePointShopCatalogFiles(temp.resolve("bp-shop"))
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        assertTrue(reloader.reload(files.load { defaults() }) is BattlePointShopCatalogReloadOutcome.Applied)
        Files.delete(temp.resolve("bp-shop/entries/apple.json"))
        val restarted = BattlePointShopCatalogFiles(temp.resolve("bp-shop"))
        assertTrue(reloader.reload(restarted.load { error("Defaults must not be read again") }) is BattlePointShopCatalogReloadOutcome.Applied)
        assertEquals(0, store.snapshot()!!.entries().size)
        assertEquals(emptyList<String>(), store.snapshot()!!.categories)
    }

    @Test
    fun `edited prices stay authoritative and deleted entries directory stays empty`() {
        val files = BattlePointShopCatalogFiles(temp.resolve("bp-shop"))
        files.load { defaults() }
        val entry = temp.resolve("bp-shop/entries/apple.json")
        Files.writeString(entry, entryJson.replace("120", "150"))
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        reloader.reload(files.load { error("Unexpected default read") })
        assertEquals(150L, store.snapshot()!!.entry("apple")!!.priceBp)
        Files.delete(entry)
        Files.delete(entry.parent)
        assertTrue(reloader.reload(files.load { error("Unexpected default read") }) is BattlePointShopCatalogReloadOutcome.Applied)
        assertTrue(store.snapshot()!!.entries().isEmpty())
    }

    private fun defaults() = BattlePointShopCatalogResourceBundle(
        listOf(CatalogResourceInput("test:mcc-bp-shop/rules/main.json") { StringReader(rulesJson) }),
        listOf(CatalogResourceInput("test:mcc-bp-shop/entries/apple.json") { StringReader(entryJson) }),
    )

    @Test
    fun `malformed external edit preserves the last valid shop without importing defaults`() {
        val files = BattlePointShopCatalogFiles(temp.resolve("bp-shop"))
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        reloader.reload(files.load { defaults() })
        val before = store.snapshot()
        Files.writeString(temp.resolve("bp-shop/entries/apple.json"), "{")
        assertTrue(reloader.reload(files.load { error("Unexpected default read") }) is BattlePointShopCatalogReloadOutcome.Rejected)
        assertSame(before, store.snapshot())
    }

    @Test
    fun `failed initial export does not publish a partial shop`() {
        val root = temp.resolve("bp-shop")
        val files = BattlePointShopCatalogFiles(root)
        val broken = defaults().copy(entries = listOf(CatalogResourceInput("test:mcc-bp-shop/entries/apple.json") {
            throw java.io.IOException("read failed")
        }))
        assertThrows(java.io.IOException::class.java) { files.load { broken } }
        assertTrue(!Files.exists(root))
        assertEquals(1, files.load { defaults() }.entries.size)
    }

    companion object {
        val rulesJson = """{"schema_version":1,"catalog_id":"mcc_core","limits":{"max_cart_lines":16,"max_quantity_per_line":64,"max_total_items":64},"categories":["misc"]}"""
        val entryJson = """{"schema_version":1,"entries":[{"entry_id":"apple","item_id":"minecraft:enchanted_golden_apple","item_count":1,"price_bp":120,"sort_order":1,"category":"misc"}]}"""
    }
}
