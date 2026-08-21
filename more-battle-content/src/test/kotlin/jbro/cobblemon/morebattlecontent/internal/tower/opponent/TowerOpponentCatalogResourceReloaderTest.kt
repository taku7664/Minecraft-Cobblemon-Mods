package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.StringReader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceReloaderTest {
    @Test
    fun `applies independent tower resource groups atomically`() {
        val store = TowerOpponentCatalogStore()

        val outcome = TowerOpponentCatalogResourceReloader(store).reload(bundledResources())

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.Applied)
        assertSame((outcome as TowerOpponentCatalogReloadOutcome.Applied).catalog, store.snapshot())
    }

    @Test
    fun `open failure closes earlier readers and preserves the previous snapshot`() {
        val store = TowerOpponentCatalogStore()
        val reloader = TowerOpponentCatalogResourceReloader(store)
        reloader.reload(bundledResources())
        val before = store.snapshot()
        val firstPath = resourceFiles(TRAINER_DIRECTORY).first()
        val first = TrackingReader(Files.readString(firstPath))

        val outcome = reloader.reload(
            TowerOpponentCatalogResourceBundle(
                trainers = listOf(
                    CatalogResourceInput("example:mbc-battle-tower/trainers/first.json") { first },
                    CatalogResourceInput("example:mbc-battle-tower/trainers/broken.json") { error("open failed") },
                ),
                pools = resourceInputs(POOL_DIRECTORY),
                encounters = resourceInputs(ENCOUNTER_DIRECTORY),
                pokemonSets = resourceInputs(POKEMON_SET_DIRECTORY),
            ),
        )

        assertTrue(outcome is TowerOpponentCatalogReloadOutcome.ReadFailed)
        assertTrue(first.closed)
        assertSame(before, store.snapshot())
    }

    private fun bundledResources() = TowerOpponentCatalogResourceBundle(
        trainers = resourceInputs(TRAINER_DIRECTORY),
        pools = resourceInputs(POOL_DIRECTORY),
        encounters = resourceInputs(ENCOUNTER_DIRECTORY),
        pokemonSets = resourceInputs(POKEMON_SET_DIRECTORY),
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
        const val TRAINER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/trainers"
        const val POOL_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pools"
        const val ENCOUNTER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/encounters"
        const val POKEMON_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pokemon-sets"
    }
}
