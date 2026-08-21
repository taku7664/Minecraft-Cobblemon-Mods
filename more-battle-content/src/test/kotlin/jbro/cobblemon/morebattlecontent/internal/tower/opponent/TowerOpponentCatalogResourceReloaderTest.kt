package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import com.google.gson.JsonParser
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceReloaderTest {
    @Test
    fun `applies independent trainer and set files atomically`() {
        val root = bundledCatalogJson()
        val profiles = root.deepCopy().apply { remove("sets") }
        val sets = root.deepCopy().apply { remove("profiles") }
        val store = TowerOpponentCatalogStore()

        val outcome = TowerOpponentCatalogResourceReloader(store).reload(
            listOf(
                CatalogResourceInput("example:battle_tower/opponents/trainers.json") { StringReader(profiles.toString()) },
                CatalogResourceInput("example:battle_tower/opponents/sets.json") { StringReader(sets.toString()) },
            ),
        )

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.Applied)
        assertSame((outcome as TowerOpponentCatalogReloadOutcome.Applied).catalog, store.snapshot())
    }

    @Test
    fun `open failure closes earlier readers and preserves the previous snapshot`() {
        val store = TowerOpponentCatalogStore()
        val reloader = TowerOpponentCatalogResourceReloader(store)
        reloader.reload { bundledCatalogReader() }
        val before = store.snapshot()
        val first = TrackingReader(bundledCatalogJson().toString())

        val outcome = reloader.reload(
            listOf(
                CatalogResourceInput("example:battle_tower/opponents/first.json") { first },
                CatalogResourceInput("example:battle_tower/opponents/broken.json") { error("open failed") },
            ),
        )

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.ReadFailed)
        assertTrue(first.closed)
        assertSame(before, store.snapshot())
    }

    @Test
    fun `applies a valid resource to the shared store`() {
        val store = TowerOpponentCatalogStore()
        val reloader = TowerOpponentCatalogResourceReloader(store)

        val outcome = reloader.reload { bundledCatalogReader() }

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.Applied)
        assertSame((outcome as TowerOpponentCatalogReloadOutcome.Applied).catalog, store.snapshot())
    }

    @Test
    fun `missing or rejected resource preserves the previous snapshot`() {
        val store = TowerOpponentCatalogStore()
        val reloader = TowerOpponentCatalogResourceReloader(store)
        reloader.reload { bundledCatalogReader() }
        val before = store.snapshot()

        val missing = reloader.reload { null }
        val rejected = reloader.reload { StringReader("{}") }

        assertSame(TowerOpponentCatalogReloadOutcome.MissingResource, missing)
        assertTrue(rejected is TowerOpponentCatalogReloadOutcome.Rejected)
        assertSame(before, store.snapshot())
    }

    @Test
    fun `resource open failure is reported without replacing the snapshot`() {
        val store = TowerOpponentCatalogStore()
        val reloader = TowerOpponentCatalogResourceReloader(store)
        reloader.reload { bundledCatalogReader() }
        val before = store.snapshot()

        val outcome = reloader.reload { throw IllegalStateException("open failed") }

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.ReadFailed)
        assertSame(before, store.snapshot())
    }

    private fun bundledCatalogReader() = checkNotNull(javaClass.getResourceAsStream(CATALOG_PATH)).reader()

    private fun bundledCatalogJson() = bundledCatalogReader().use(JsonParser::parseReader).asJsonObject

    private class TrackingReader(value: String) : StringReader(value) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val CATALOG_PATH =
            "/data/cobblemon_more_battle_content/battle_tower/opponents/mbc_core.json"
    }
}
