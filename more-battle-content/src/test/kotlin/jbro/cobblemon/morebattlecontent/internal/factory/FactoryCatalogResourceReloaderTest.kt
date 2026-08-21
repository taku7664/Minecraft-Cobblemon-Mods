package jbro.cobblemon.morebattlecontent.internal.factory

import com.google.gson.JsonParser
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogResourceReloaderTest {
    @Test
    fun `applies independent trainer and rental files atomically`() {
        val root = bundledCatalogJson()
        val trainers = root.deepCopy().apply { remove("sets") }
        val sets = root.deepCopy().apply { remove("trainers") }
        val store = FactoryCatalogStore()

        val outcome = FactoryCatalogResourceReloader(store).reload(
            listOf(
                CatalogResourceInput("example:battle_factory/catalog/trainers.json") { StringReader(trainers.toString()) },
                CatalogResourceInput("example:battle_factory/catalog/rentals.json") { StringReader(sets.toString()) },
            ),
        )

        assertTrue(outcome is FactoryCatalogReloadOutcome.Applied)
        assertSame((outcome as FactoryCatalogReloadOutcome.Applied).catalog, store.snapshot())
    }

    @Test
    fun `open failure closes earlier readers and preserves the previous snapshot`() {
        val store = FactoryCatalogStore()
        FactoryCatalogResourceReloader(store).reload { bundledCatalogJson().toString().reader() }
        val before = store.snapshot()
        val first = TrackingReader(bundledCatalogJson().toString())

        val outcome = FactoryCatalogResourceReloader(store).reload(
            listOf(
                CatalogResourceInput("example:battle_factory/catalog/first.json") { first },
                CatalogResourceInput("example:battle_factory/catalog/broken.json") { error("open failed") },
            ),
        )

        assertTrue(outcome is FactoryCatalogReloadOutcome.ReadFailed)
        assertTrue(first.closed)
        assertSame(before, store.snapshot())
    }

    private fun bundledCatalogJson() = checkNotNull(javaClass.getResourceAsStream(CATALOG_PATH)).reader().use(JsonParser::parseReader).asJsonObject

    private class TrackingReader(value: String) : StringReader(value) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val CATALOG_PATH = "/data/cobblemon_more_battle_content/battle_factory/catalog/mbc_core.json"
    }
}
