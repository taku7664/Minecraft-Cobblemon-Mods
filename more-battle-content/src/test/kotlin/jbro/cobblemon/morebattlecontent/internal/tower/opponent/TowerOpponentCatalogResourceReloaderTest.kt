package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceReloaderTest {
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

    private companion object {
        const val CATALOG_PATH =
            "/data/cobblemon_more_battle_content/battle_tower/opponents/mbc_core.json"
    }
}
