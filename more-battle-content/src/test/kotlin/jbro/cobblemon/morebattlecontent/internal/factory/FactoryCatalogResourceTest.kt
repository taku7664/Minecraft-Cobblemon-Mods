package jbro.cobblemon.morebattlecontent.internal.factory

import com.cobblemon.mod.common.api.pokemon.Natures
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryCatalogResourceTest {
    private val deterministicRandom = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `bundled schema four catalog contains two thousand complete fixed presets`() {
        val catalog = bundledCatalog()
        val pools = approvedPools(catalog)
        val allSets = pools.values.flatten()

        assertEquals("merged_factory_catalog", catalog.catalogId)
        assertEquals(EXPECTED_POOL_SIZES, pools.mapValues { it.value.size })
        assertEquals(2_004, allSets.map(FactoryRentalTemplate::setId).distinct().size)
        assertEquals(501, allSets.groupBy { it.speciesId to it.formId }.size)
        assertTrue(allSets.groupBy { it.speciesId to it.formId }.values.all { it.size == 4 })
        assertTrue(allSets.all { it.moveIds.size == 4 && it.moveIds.distinct().size == 4 })
        assertTrue(allSets.all { it.preferredMoveIds.all(it.moveIds::contains) })
        assertTrue(allSets.all { it.roles.isNotEmpty() })
        assertTrue(allSets.all { it.ivs == null })
        assertEquals(84, catalog.trainersFor(FactoryBattleFormat.SINGLE).size)
        assertEquals(84, catalog.trainersFor(FactoryBattleFormat.DOUBLE).size)

        resourceFiles(RENTAL_SET_DIRECTORY).forEach { path ->
            val root = Files.newBufferedReader(path).use(JsonParser::parseReader).asJsonObject
            assertEquals(4, root["schema_version"].asInt)
            root.getAsJsonArray("rental_sets").forEach { element ->
                val set = element.asJsonObject
                assertTrue(!set.has("move_slots"))
                assertTrue(!set.has("held_items"))
                assertTrue(!set.has("nature_pool"))
                assertTrue(set.has("moves") && set.has("held_item_id") && set.has("nature_id"))
            }
        }
    }

    @Test
    fun `every bundled fixed move is learnable by its exact Cobblemon species or form`() {
        val sets = approvedPools(bundledCatalog()).values.flatten()
        cobblemonJar().use { jar ->
            val speciesEntries = jar.entries().asSequence()
                .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }

            sets.forEach { template ->
                val speciesName = template.speciesId.substringAfter(':')
                val entry = requireNotNull(speciesEntries[speciesName]) { "Missing Cobblemon species JSON for ${template.speciesId}" }
                val root = jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                val learnset = linkedSetOf<String>()
                root.getAsJsonArray("moves")?.forEach { learnset += it.asString.substringAfter(':').normalizedId() }
                template.formId?.let { formId ->
                    root.getAsJsonArray("forms")
                        ?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"].asString.equals(formId, ignoreCase = true) }
                        ?.getAsJsonArray("moves")
                        ?.forEach { learnset += it.asString.substringAfter(':').normalizedId() }
                }
                template.moveIds.forEach { moveId ->
                    assertTrue(moveId.substringAfter(':').normalizedId() in learnset, "${template.setId} cannot learn $moveId")
                }
            }
        }
    }

    @Test
    fun `every bundled ability belongs to its exact Cobblemon species or form`() {
        val sets = approvedPools(bundledCatalog()).values.flatten()
        cobblemonJar().use { jar ->
            val speciesEntries = jar.entries().asSequence()
                .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }

            sets.forEach { template ->
                val speciesName = template.speciesId.substringAfter(':')
                val entry = requireNotNull(speciesEntries[speciesName])
                val root = jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                val form = template.formId?.let { formId ->
                    root.getAsJsonArray("forms")?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"].asString.equals(formId, ignoreCase = true) }
                }
                val owner = form?.takeIf { it.has("abilities") } ?: root
                val abilities = owner.getAsJsonArray("abilities").map { it.asString.removePrefix("h:").normalizedId() }.toSet()
                assertTrue(template.abilityId.substringAfter(':').normalizedId() in abilities, "${template.setId} cannot have ${template.abilityId}")
            }
        }
    }

    @Test
    fun `all fixed natures and held items resolve in the Cobblemon runtime`() {
        val sets = approvedPools(bundledCatalog()).values.flatten()
        val natures = Natures.all().map { it.name.toString() }.toSet()
        cobblemonJar().use { jar ->
            val languageEntry = requireNotNull(jar.getJarEntry("assets/cobblemon/lang/en_us.json"))
            val language = jar.getInputStream(languageEntry).reader().use(JsonParser::parseReader).asJsonObject
            sets.forEach { template ->
                assertTrue(template.natureId in natures, "Unknown nature ${template.natureId}")
                val itemPath = template.heldItemId.substringAfter(':')
                assertTrue(language.has("item.cobblemon.$itemPath"), "Unknown held item ${template.heldItemId}")
            }
        }
    }

    @Test
    fun `every progression window can draft and field both formats`() {
        val catalog = bundledCatalog()
        val draftSelector = FactoryDraftSelector(catalog, deterministicRandom)
        val opponentSelector = FactoryOpponentSelector(catalog, deterministicRandom)
        val rounds = mapOf(FactoryLevelMode.LEVEL_50 to 1..8, FactoryLevelMode.OPEN_LEVEL to 1..5)

        rounds.forEach { (levelMode, supportedRounds) ->
            supportedRounds.forEach { round ->
                RENT_AND_TRADE_COUNTS.forEach { count ->
                    assertNotNull(draftSelector.select(levelMode, round, count), "$levelMode round $round count $count")
                }
                FactoryBattleFormat.entries.forEach { format ->
                    assertTrue(
                        opponentSelector.select(format, levelMode, round) is FactoryOpponentSelectionResult.Selected,
                        "$format $levelMode round $round",
                    )
                }
            }
        }
    }

    @Test
    fun `every bundled trainer has unique translated names and a shared explanation`() {
        val catalog = bundledCatalog()
        val english = language("en_us")
        val korean = language("ko_kr")
        val trainers = catalog.trainersFor(FactoryBattleFormat.SINGLE)

        trainers.forEach { trainer ->
            assertTrue(english[trainer.displayNameKey].asString.isNotBlank(), trainer.displayNameKey)
            assertTrue(korean[trainer.displayNameKey].asString.isNotBlank(), trainer.displayNameKey)
            assertTrue(english[trainer.descriptionKey].asString.isNotBlank(), trainer.descriptionKey)
            assertTrue(korean[trainer.descriptionKey].asString.isNotBlank(), trainer.descriptionKey)
        }
        assertEquals(trainers.size, trainers.map { english[it.displayNameKey].asString }.distinct().size)
        assertEquals(trainers.size, trainers.map { korean[it.displayNameKey].asString }.distinct().size)
    }

    @Test
    fun `bundled catalog survives ten thousand seeded draft and opponent selections without mutating presets`() {
        val catalog = bundledCatalog()
        val seeded = Random(0x4D4243)
        val random = object : FactoryCatalogRandom {
            override fun nextLong(bound: Long): Long = seeded.nextLong(bound)
            override fun nextInt(bound: Int): Int = seeded.nextInt(bound)
        }
        val draftSelector = FactoryDraftSelector(catalog, random)
        val opponentSelector = FactoryOpponentSelector(catalog, random)
        val templates = approvedPools(catalog).values.flatten().associateBy(FactoryRentalTemplate::setId)

        repeat(10_000) { index ->
            val levelMode = FactoryLevelMode.entries[index % FactoryLevelMode.entries.size]
            val round = when (levelMode) {
                FactoryLevelMode.LEVEL_50 -> index % 12 + 1
                FactoryLevelMode.OPEN_LEVEL -> index % 8 + 1
            }
            val draft = requireNotNull(draftSelector.select(levelMode, round, RENT_AND_TRADE_COUNTS[index % RENT_AND_TRADE_COUNTS.size]))
            assertEquals(6, draft.sets.map(FactoryRentalSet::speciesId).distinct().size)
            assertEquals(6, draft.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
            draft.sets.forEach { rental ->
                val template = templates.getValue(rental.setId)
                assertEquals(template.moveIds, rental.moveIds)
                assertEquals(template.heldItemId, rental.heldItemId)
                assertEquals(template.natureId, rental.natureId)
            }

            val format = FactoryBattleFormat.entries[index % FactoryBattleFormat.entries.size]
            val selected = opponentSelector.select(format, levelMode, round) as FactoryOpponentSelectionResult.Selected
            assertEquals(format.selectionSize, selected.team.map(FactoryRentalSet::speciesId).distinct().size)
            assertEquals(format.selectionSize, selected.team.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
        }
    }

    private fun bundledCatalog(): FactoryCatalog {
        val result = FactoryCatalogLoader.loadSeparated(
            fragmentReaders(TRAINER_DIRECTORY),
            fragmentReaders(RENTAL_SET_DIRECTORY),
        )
        assertTrue(result is FactoryCatalogLoadResult.Loaded, "Bundled Battle Factory catalog was rejected: $result")
        return (result as FactoryCatalogLoadResult.Loaded).catalog
    }

    private fun fragmentReaders(directory: String): List<Pair<String, Reader>> =
        resourceFiles(directory).map { path -> path.fileName.toString() to Files.newBufferedReader(path) }

    private fun resourceFiles(directory: String): List<Path> {
        val url = javaClass.getResource(directory)
        assertNotNull(url, "Missing bundled resource directory: $directory")
        return Files.list(Paths.get(url!!.toURI())).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
        }
    }

    private fun approvedPools(catalog: FactoryCatalog): Map<String, List<FactoryRentalTemplate>> = linkedMapOf(
        "starter-1" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.STARTER, setOf(1))),
        "intermediate-1" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(1))),
        "intermediate-2" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(2))),
        "advanced-1" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1))),
        "advanced-2" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(2))),
        "advanced-3" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(3))),
        "advanced-4" to catalog.rentalPool(FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(4))),
    )

    private fun language(code: String) = javaClass.getResourceAsStream(
        "/assets/cobblemon_more_battle_content/lang/$code.json",
    )!!.use { JsonParser.parseReader(InputStreamReader(it)).asJsonObject }

    private fun cobblemonJar(): JarFile {
        val codeSource = Paths.get(com.cobblemon.mod.common.api.pokemon.PokemonSpecies::class.java.protectionDomain.codeSource.location.toURI())
        if (codeSource.toFile().isFile) return JarFile(codeSource.toFile())
        val candidate = System.getProperty("java.class.path").split(System.getProperty("path.separator"))
            .asSequence().map(Paths::get).filter { it.toFile().isFile && it.fileName.toString().contains("cobblemon", true) }
            .firstOrNull { path ->
                runCatching { JarFile(path.toFile()).use { it.getEntry("data/cobblemon/species/generation1/arcanine.json") != null } }.getOrDefault(false)
            }
        return JarFile(requireNotNull(candidate) { "Could not locate the Cobblemon runtime JAR" }.toFile())
    }

    private fun String.normalizedId(): String = lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        const val TRAINER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-factory/trainers"
        const val RENTAL_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-factory/rental-sets"
        val RENT_AND_TRADE_COUNTS = listOf(1, 7, 14, 21, 28, 35)
        val EXPECTED_POOL_SIZES = mapOf(
            "starter-1" to 120,
            "intermediate-1" to 210,
            "intermediate-2" to 320,
            "advanced-1" to 397,
            "advanced-2" to 320,
            "advanced-3" to 268,
            "advanced-4" to 369,
        )
    }
}
