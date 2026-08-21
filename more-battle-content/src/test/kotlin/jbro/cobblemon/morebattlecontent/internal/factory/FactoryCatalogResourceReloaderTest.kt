package jbro.cobblemon.morebattlecontent.internal.factory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogResourceReloaderTest {
    @Test
    fun `applies independent trainer and rental files atomically`() {
        val store = FactoryCatalogStore()

        val outcome = FactoryCatalogResourceReloader(store).reload(bundledResources())

        assertTrue(outcome is FactoryCatalogReloadOutcome.Applied)
        assertSame((outcome as FactoryCatalogReloadOutcome.Applied).catalog, store.snapshot())
    }

    @Test
    fun `open failure closes earlier readers and preserves the previous snapshot`() {
        val store = FactoryCatalogStore()
        val reloader = FactoryCatalogResourceReloader(store)
        reloader.reload(bundledResources())
        val before = store.snapshot()
        val trainerFile = resourceFiles(TRAINER_DIRECTORY).first()
        val first = TrackingReader(Files.readString(trainerFile))

        val outcome = reloader.reload(
            FactoryCatalogResourceBundle(
                trainers = listOf(
                    CatalogResourceInput("example:mbc-battle-factory/trainers/first.json") { first },
                    CatalogResourceInput("example:mbc-battle-factory/trainers/broken.json") { error("open failed") },
                ),
                rentalSets = resourceInputs(RENTAL_SET_DIRECTORY),
            ),
        )

        assertTrue(outcome is FactoryCatalogReloadOutcome.ReadFailed)
        assertTrue(first.closed)
        assertSame(before, store.snapshot())
    }

    private fun bundledResources() = FactoryCatalogResourceBundle(
        trainers = resourceInputs(TRAINER_DIRECTORY),
        rentalSets = resourceInputs(RENTAL_SET_DIRECTORY),
    )

    private fun resourceInputs(directory: String): List<CatalogResourceInput> = resourceFiles(directory).map { path ->
        CatalogResourceInput(path.fileName.toString()) { Files.newBufferedReader(path) }
    }

    private fun resourceFiles(directory: String): List<Path> {
        val url = checkNotNull(javaClass.getResource(directory))
        return Files.list(Paths.get(url.toURI())).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
        }
    }

    private class TrackingReader(value: String) : StringReader(value) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val TRAINER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-factory/trainers"
        const val RENTAL_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-factory/rental-sets"
    }
}
