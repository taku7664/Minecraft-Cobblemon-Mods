package jbro.cobblemon.morebattlecontent.internal.factory

import com.google.gson.JsonParser
import com.cobblemon.mod.common.api.pokemon.Natures
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FactoryCatalogResourceTest {
    private val deterministicRandom = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = 0
        override fun nextInt(bound: Int): Int = 0
    }

    @Test
    fun `bundled schema three catalog contains randomized competitive templates`() {
        val catalog = bundledCatalog()
        val pools = approvedPools(catalog)
        val allSets = pools.values.flatten()

        val root = javaClass.getResourceAsStream(CATALOG_PATH)!!.reader().use(JsonParser::parseReader).asJsonObject

        assertEquals("mbc_factory_core", catalog.catalogId)
        assertEquals(3, root["schema_version"].asInt)
        assertEquals(7, pools.size)
        pools.forEach { (id, sets) ->
            assertEquals(EXPECTED_POOL_SIZES.getValue(id), sets.size, id)
            assertEquals(sets.size, sets.map(FactoryRentalTemplate::speciesId).distinct().size, id)
            assertTrue(sets.all { it.heldItemIds.isNotEmpty() }, id)
        }
        assertEquals(391, allSets.map(FactoryRentalTemplate::setId).distinct().size)
        assertTrue(allSets.map(FactoryRentalTemplate::speciesId).distinct().size >= 100)
        assertTrue(allSets.all { it.ivs != null })
        assertTrue(allSets.all { it.moveSlots.size == 4 })
        assertTrue(allSets.count { template -> template.moveSlots.any { it.size > 1 } } >= 112)
        assertTrue(allSets.all { it.natureIds == FactoryNaturePool.ALL })
        assertTrue(allSets.all { it.heldItemIds.distinct().size == it.heldItemIds.size })
        assertEquals(84, catalog.conceptsFor(FactoryBattleFormat.SINGLE).size)
        assertEquals(84, catalog.conceptsFor(FactoryBattleFormat.DOUBLE).size)
        catalog.conceptsFor(FactoryBattleFormat.SINGLE).forEach { concept ->
            assertTrue(concept.members.all { it.setIds.size >= 4 }, concept.conceptId)
        }
        assertEquals(1, allSets.count { it.speciesId == "cobblemon:porygon2" })
        assertEquals(listOf("cobblemon:eviolite"), allSets.single { it.speciesId == "cobblemon:porygon2" }.heldItemIds)
        assertTrue(allSets.none { it.speciesId == "cobblemon:jirachi" })
        assertEquals(
            setOf("cobblemon:latios", "cobblemon:zapdos", "cobblemon:articuno", "cobblemon:registeel", "cobblemon:regigigas"),
            pools.getValue("advanced-4").map(FactoryRentalTemplate::speciesId).toSet().intersect(APPROVED_LATE_LEGENDARIES),
        )

        val byId = allSets.associateBy(FactoryRentalTemplate::setId)
        ROTOM_WASH_SET_IDS.forEach { assertEquals("wash", byId.getValue(it).formId, it) }
        ROTOM_HEAT_SET_IDS.forEach { assertEquals("heat", byId.getValue(it).formId, it) }
        assertEquals("alola", byId.getValue("i1_ninetales_alola").formId)
        assertTrue(allSets.filterNot {
            it.setId in ROTOM_WASH_SET_IDS || it.setId in ROTOM_HEAT_SET_IDS || it.setId == "i1_ninetales_alola"
        }
            .all { it.formId == null })
    }

    @Test
    fun `every bundled move candidate is learnable by its exact Cobblemon species or form`() {
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
                root.getAsJsonArray("moves")?.forEach { learnset += it.asString.substringAfter(':') }
                template.formId?.let { formId ->
                    root.getAsJsonArray("forms")
                        ?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"].asString.equals(formId, ignoreCase = true) }
                        ?.getAsJsonArray("moves")
                        ?.forEach { learnset += it.asString.substringAfter(':') }
                }
                template.moveSlots.flatten().forEach { moveId ->
                    assertTrue(moveId.substringAfter(':') in learnset, "${template.setId} cannot learn $moveId")
                }
            }
        }
    }

    @Test
    fun `every bundled ability id is canonical and belongs to its exact Cobblemon species or form`() {
        val sets = approvedPools(bundledCatalog()).values.flatten()
        cobblemonJar().use { jar ->
            val speciesEntries = jar.entries().asSequence()
                .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }

            sets.forEach { template ->
                val speciesName = template.speciesId.substringAfter(':')
                val entry = requireNotNull(speciesEntries[speciesName]) { "Missing Cobblemon species JSON for ${template.speciesId}" }
                val root = jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                val form = template.formId?.let { formId ->
                    root.getAsJsonArray("forms")
                        ?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"].asString.equals(formId, ignoreCase = true) }
                }
                val abilityOwner = form?.takeIf { it.has("abilities") } ?: root
                val abilities = abilityOwner.getAsJsonArray("abilities")
                    .map { it.asString.removePrefix("h:") }
                    .toSet()
                val abilityName = template.abilityId.substringAfter(':')

                assertTrue(abilityName in abilities, "${template.setId} cannot have ${template.abilityId}; allowed=$abilities")
            }
        }
    }

    @Test
    fun `all nature pool exactly matches Cobblemon standard natures`() {
        assertEquals(Natures.all().map { it.name.toString() }.toSet(), FactoryNaturePool.ALL.toSet())
    }

    @Test
    fun `every original progression window can draft and field both formats`() {
        val catalog = bundledCatalog()
        val draftSelector = FactoryDraftSelector(catalog, deterministicRandom)
        val opponentSelector = FactoryOpponentSelector(catalog, deterministicRandom)

        val rounds = mapOf(
            FactoryLevelMode.LEVEL_50 to 1..8,
            FactoryLevelMode.OPEN_LEVEL to 1..5,
        )
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
    fun `every bundled concept uses an ordinary trainer name and has an explanation`() {
        val catalog = bundledCatalog()
        val english = language("en_us")
        val korean = language("ko_kr")
        val concepts = catalog.conceptsFor(FactoryBattleFormat.SINGLE)

        concepts.forEach { concept ->
            EXPECTED_TRAINER_NAMES[concept.conceptId]?.let { names ->
                assertEquals(names.first, english[concept.displayNameKey].asString, concept.displayNameKey)
                assertEquals(names.second, korean[concept.displayNameKey].asString, concept.displayNameKey)
            }
            assertTrue(english[concept.descriptionKey].asString.isNotBlank(), concept.descriptionKey)
            assertTrue(korean[concept.descriptionKey].asString.isNotBlank(), concept.descriptionKey)
        }
        assertEquals(84, concepts.size)
        assertEquals(concepts.size, concepts.map { english[it.displayNameKey].asString }.distinct().size)
        assertEquals(concepts.size, concepts.map { korean[it.displayNameKey].asString }.distinct().size)
    }

    @Test
    fun `bundled catalog survives ten thousand seeded draft and opponent selections`() {
        val catalog = bundledCatalog()
        val seeded = Random(0x4D4243)
        val random = object : FactoryCatalogRandom {
            override fun nextLong(bound: Long): Long = seeded.nextLong(bound)
            override fun nextInt(bound: Int): Int = seeded.nextInt(bound)
        }
        val draftSelector = FactoryDraftSelector(catalog, random)
        val opponentSelector = FactoryOpponentSelector(catalog, random)

        repeat(10_000) { index ->
            val levelMode = FactoryLevelMode.entries[index % FactoryLevelMode.entries.size]
            val round = when (levelMode) {
                FactoryLevelMode.LEVEL_50 -> index % 12 + 1
                FactoryLevelMode.OPEN_LEVEL -> index % 8 + 1
            }
            val count = RENT_AND_TRADE_COUNTS[index % RENT_AND_TRADE_COUNTS.size]
            val draft = requireNotNull(draftSelector.select(levelMode, round, count))
            assertEquals(6, draft.sets.map(FactoryRentalSet::speciesId).distinct().size)
            assertEquals(6, draft.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)

            val format = FactoryBattleFormat.entries[index % FactoryBattleFormat.entries.size]
            val selected = opponentSelector.select(format, levelMode, round) as FactoryOpponentSelectionResult.Selected
            assertEquals(format.selectionSize, selected.team.size)
            assertEquals(format.selectionSize, selected.team.map(FactoryRentalSet::speciesId).distinct().size)
            assertEquals(format.selectionSize, selected.team.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
            assertEquals(format.selectionSize, selected.strategy.members.size)
        }
    }

    private fun bundledCatalog(): FactoryCatalog {
        val stream = javaClass.getResourceAsStream(CATALOG_PATH)
        assertNotNull(stream, "Missing bundled Battle Factory catalog")
        val result = stream!!.reader().use(FactoryCatalogLoader::load)
        assertTrue(result is FactoryCatalogLoadResult.Loaded, "Bundled Battle Factory catalog was rejected: $result")
        return (result as FactoryCatalogLoadResult.Loaded).catalog
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
        val candidate = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .asSequence()
            .map(Paths::get)
            .filter { it.toFile().isFile && it.fileName.toString().contains("cobblemon", ignoreCase = true) }
            .firstOrNull { path ->
                runCatching { JarFile(path.toFile()).use { it.getEntry("data/cobblemon/species/generation1/arcanine.json") != null } }
                    .getOrDefault(false)
            }
        return JarFile(requireNotNull(candidate) { "Could not locate the Cobblemon runtime JAR" }.toFile())
    }

    private companion object {
        const val CATALOG_PATH = "/data/cobblemon_more_battle_content/battle_factory/catalog/mbc_core.json"
        val ROTOM_WASH_SET_IDS = setOf("s1_rotom_wash", "i2_rotom_wash")
        val ROTOM_HEAT_SET_IDS = setOf("i1_rotom_heat", "a2_rotom_heat")
        val RENT_AND_TRADE_COUNTS = listOf(1, 7, 14, 21, 28, 35)
        val APPROVED_LATE_LEGENDARIES = setOf(
            "cobblemon:latios",
            "cobblemon:zapdos",
            "cobblemon:articuno",
            "cobblemon:registeel",
            "cobblemon:regigigas",
        )
        val EXPECTED_POOL_SIZES = mapOf(
            "starter-1" to 58,
            "intermediate-1" to 57,
            "intermediate-2" to 50,
            "advanced-1" to 59,
            "advanced-2" to 59,
            "advanced-3" to 53,
            "advanced-4" to 55,
        )
        val EXPECTED_TRAINER_NAMES = linkedMapOf(
            "arcanine_voltage" to ("Alex" to "준서"),
            "feraligatr_screen" to ("Maya" to "민서"),
            "heracross_rotation" to ("Ben" to "우진"),
            "porygon_balance" to ("Claire" to "소연"),
            "gyarados_screen" to ("Daniel" to "재현"),
            "volcarona_tailwind" to ("Alice" to "아린"),
            "lucario_web" to ("Chris" to "성민"),
            "dragonite_snow" to ("Julia" to "유진"),
            "garchomp_followme" to ("Kevin" to "동현"),
            "excadrill_sand" to ("Sarah" to "은서"),
            "ceruledge_pivot" to ("Adam" to "민재"),
            "samurott_rain" to ("Nina" to "지아"),
            "salamence_pressure" to ("Eric" to "태현"),
            "empoleon_poison" to ("Laura" to "서윤"),
            "reuniclus_room" to ("James" to "준영"),
            "kommoo_room" to ("Amy" to "하윤"),
            "metagross_disruption" to ("David" to "정우"),
            "tyranitar_veil" to ("Zoe" to "예나"),
            "cloyster_control" to ("Ian" to "시현"),
            "serperior_special" to ("Kate" to "다인"),
            "garchomp_redirection" to ("Evan" to "규민"),
            "volcarona_rain" to ("Ruby" to "수아"),
            "samurott_mixed" to ("Aaron" to "진우"),
            "glimmora_hazard" to ("Lucy" to "윤서"),
            "latios_offense" to ("Henry" to "현준"),
            "zapdos_pivot" to ("Anna" to "가은"),
            "articuno_balance" to ("Dylan" to "도현"),
            "regigigas_control" to ("Emily" to "채영"),
        )
    }
}
