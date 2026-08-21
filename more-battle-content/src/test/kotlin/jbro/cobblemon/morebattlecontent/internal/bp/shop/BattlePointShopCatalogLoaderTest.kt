package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointShopCatalogLoaderTest {
    private val existingItems = setOf("cobblemon:choice_band", "cobblemon:life_orb")

    @Test
    fun `loads a strict catalog and derives a stable content revision`() {
        val first = loaded(validJson())
        val second = loaded(validJson())

        assertEquals("mbc_core", first.catalogId)
        assertEquals(first.revision, second.revision)
        assertEquals(64, first.limits.maxTotalItems)
        assertEquals(listOf("choice_band", "life_orb"), first.entries().map { it.entryId })
        assertEquals(25, first.entry("choice_band")?.priceBp)
    }

    @Test
    fun `revision changes when purchase semantics change`() {
        val first = loaded(validJson())
        val second = loaded(validJson().replace("\"price_bp\": 25", "\"price_bp\": 26"))

        assertNotEquals(first.revision, second.revision)
    }

    @Test
    fun `rejects malformed schemas unknown fields and unavailable items`() {
        assertRejected("{")
        assertRejected(validJson().replace("\"schema_version\": 1", "\"schema_version\": 2"))
        assertRejected(validJson().replace("\"catalog_id\": \"mbc_core\"", "\"catalog_id\": \"mbc_core\", \"extra\": true"))
        assertRejected(validJson().replace("cobblemon:life_orb", "missing:item"))
    }

    @Test
    fun `rejects duplicate ids sort orders and invalid limits or values`() {
        assertRejected(validJson().replace("\"entry_id\": \"life_orb\"", "\"entry_id\": \"choice_band\""))
        assertRejected(validJson().replace("\"sort_order\": 20", "\"sort_order\": 10"))
        assertRejected(validJson().replace("\"max_cart_lines\": 16", "\"max_cart_lines\": 0"))
        assertRejected(validJson().replace("\"item_count\": 1", "\"item_count\": 0"))
        assertRejected(validJson().replace("\"price_bp\": 25", "\"price_bp\": 0"))
    }

    @Test
    fun `failed reload preserves the last valid immutable snapshot`() {
        val store = BattlePointShopCatalogStore(existingItems::contains)
        val accepted = store.reload(StringReader(validJson()))
        val snapshot = store.snapshot()
        val rejected = store.reload(StringReader("{}"))

        assertTrue(accepted is BattlePointShopCatalogLoadResult.Loaded)
        assertTrue(rejected is BattlePointShopCatalogLoadResult.Rejected)
        assertSame(snapshot, store.snapshot())
    }

    @Test
    fun `loads independent rules and entry fragments`() {
        val loaded = BattlePointShopCatalogLoader.loadSeparated(
            ruleFragments = listOf("example:mbc-bp-shop/rules/mbc-core.json" to StringReader(validRulesJson())),
            entryFragments = listOf(
                "example:mbc-bp-shop/entries/choice-band.json" to StringReader(validEntryJson("choice_band", 10)),
                "example:mbc-bp-shop/entries/life-orb.json" to StringReader(validEntryJson("life_orb", 20)),
            ),
            itemExists = existingItems::contains,
        )

        val catalog = (loaded as BattlePointShopCatalogLoadResult.Loaded).catalog
        assertEquals("mbc_core", catalog.catalogId)
        assertEquals(64, catalog.limits.maxTotalItems)
        assertEquals(listOf("choice_band", "life_orb"), catalog.entries().map { it.entryId })
    }

    @Test
    fun `separated reload rejects duplicate entries and preserves the previous snapshot`() {
        val store = BattlePointShopCatalogStore(existingItems::contains)
        store.reload(StringReader(validJson()))
        val snapshot = store.snapshot()

        val result = store.reloadSeparated(
            ruleFragments = listOf("example:mbc-bp-shop/rules/mbc-core.json" to StringReader(validRulesJson())),
            entryFragments = listOf(
                "example:mbc-bp-shop/entries/first.json" to StringReader(validEntryJson("choice_band", 10)),
                "example:mbc-bp-shop/entries/duplicate.json" to StringReader(validEntryJson("choice_band", 20)),
            ),
        )

        assertTrue(result is BattlePointShopCatalogLoadResult.Rejected)
        assertSame(snapshot, store.snapshot())
    }

    private fun loaded(json: String): BattlePointShopCatalog =
        (BattlePointShopCatalogLoader.load(StringReader(json), existingItems::contains) as
            BattlePointShopCatalogLoadResult.Loaded).catalog

    private fun assertRejected(json: String) {
        assertTrue(
            BattlePointShopCatalogLoader.load(StringReader(json), existingItems::contains) is
                BattlePointShopCatalogLoadResult.Rejected,
        )
    }

    private fun validJson() =
        """
        {
          "schema_version": 1,
          "catalog_id": "mbc_core",
          "limits": {
            "max_cart_lines": 16,
            "max_quantity_per_line": 64,
            "max_total_items": 64
          },
          "entries": [
            {
              "entry_id": "choice_band",
              "item_id": "cobblemon:choice_band",
              "item_count": 1,
              "price_bp": 25,
              "sort_order": 10
            },
            {
              "entry_id": "life_orb",
              "item_id": "cobblemon:life_orb",
              "item_count": 1,
              "price_bp": 25,
              "sort_order": 20
            }
          ]
        }
        """.trimIndent()

    private fun validRulesJson() =
        """
        {
          "schema_version": 1,
          "catalog_id": "mbc_core",
          "limits": {
            "max_cart_lines": 16,
            "max_quantity_per_line": 64,
            "max_total_items": 64
          }
        }
        """.trimIndent()

    private fun validEntryJson(entryId: String, sortOrder: Int) =
        """
        {
          "schema_version": 1,
          "entries": [
            {
              "entry_id": "$entryId",
              "item_id": "cobblemon:$entryId",
              "item_count": 1,
              "price_bp": 25,
              "sort_order": $sortOrder
            }
          ]
        }
        """.trimIndent()
}
