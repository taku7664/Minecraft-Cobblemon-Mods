package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.io.StringReader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourceReloaderTest {
    @Test
    fun `linkage failure closes earlier readers and preserves the previous catalog`() {
        val store = BattlePointShopCatalogStore { true }
        val reloader = BattlePointShopCatalogResourceReloader(store)
        assertTrue(reloader.reload(validBundle()) is BattlePointShopCatalogReloadOutcome.Applied)
        val before = store.snapshot()
        val first = TrackingReader(rulesJson)
        val failure = NoSuchMethodError("resource API drift")

        val outcome = reloader.reload(
            BattlePointShopCatalogResourceBundle(
                rules = listOf(
                    CatalogResourceInput("first-rules.json") { first },
                    CatalogResourceInput("broken-rules.json") { throw failure },
                ),
                entries = listOf(CatalogResourceInput("entry.json") { StringReader(entryJson) }),
            ),
        ) as BattlePointShopCatalogReloadOutcome.ReadFailed

        assertSame(failure, outcome.cause)
        assertTrue(first.closed)
        assertSame(before, store.snapshot())
    }

    private fun validBundle() = BattlePointShopCatalogResourceBundle(
        rules = listOf(CatalogResourceInput("rules.json") { StringReader(rulesJson) }),
        entries = listOf(CatalogResourceInput("entry.json") { StringReader(entryJson) }),
    )

    private class TrackingReader(value: String) : StringReader(value) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        val rulesJson = """
            {
              "schema_version": 1,
              "catalog_id": "mbc_core",
              "limits": { "max_cart_lines": 16, "max_quantity_per_line": 64, "max_total_items": 64 }
            }
        """.trimIndent()

        val entryJson = """
            {
              "schema_version": 1,
              "entries": [
                {
                  "entry_id": "choice_band",
                  "item_id": "cobblemon:choice_band",
                  "item_count": 1,
                  "price_bp": 25,
                  "sort_order": 10
                }
              ]
            }
        """.trimIndent()
    }
}
