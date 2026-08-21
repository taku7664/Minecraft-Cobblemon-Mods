package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceTest {
    @Test
    fun `every reachable tower battle has at least fifty eligible trainers`() {
        val catalog = bundledCatalog()

        TowerRank.entries.forEach { rank ->
            val reachableKinds = buildList {
                add(TowerOpponentKind.REGULAR)
                if (rank.completionChangesTier) add(TowerOpponentKind.TIER_BOSS)
                if (rank == TowerRank.MAX) add(TowerOpponentKind.MASTER_BALL_BOSS)
            }
            TowerBattleFormat.entries.forEach { format ->
                MajorBattleMechanic.entries.forEach { mechanic ->
                    reachableKinds.forEach { kind ->
                        val profiles = catalog.profilesFor(rank, format, kind, mechanic)
                        assertTrue(
                            profiles.size >= MINIMUM_TRAINERS_PER_CATEGORY,
                            "$rank $format $kind $mechanic has only ${profiles.size} eligible trainers",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `bundled catalog provides fifty real trainers and broad species pools per category`() {
        val catalog = bundledCatalog()
        val profiles = approvedProfiles(catalog)

        assertTrue(profiles.map(TowerOpponentProfile::profileId).toSet().containsAll(setOf("trainer_001", "trainer_050")))
        val categories = profiles.groupBy {
            listOf(it.rankIds, it.format, it.opponentKind, it.mechanic, it.theme)
        }
        assertEquals(EXPECTED_PROFILE_CATEGORY_COUNT, categories.size)
        categories.forEach { (category, trainers) ->
            assertEquals(MINIMUM_TRAINERS_PER_CATEGORY, trainers.size, category.toString())
            assertEquals(1, trainers.map { it.setIds.sorted() }.distinct().size, "Trainers in $category must share its rule-driven pool")
        }
        profiles.forEach { profile ->
            assertTrue(profile.setIds.size >= MINIMUM_SPECIES_PER_MECHANIC_TIER)
            assertEquals(profile.setIds.size, profile.setIds.distinct().size)
        }

        val regularProfiles = profiles.filter { it.opponentKind == TowerOpponentKind.REGULAR }
        val uniqueSets = regularProfiles.flatMap(catalog::setsFor).distinctBy(TowerPokemonSet::setId)
        val speciesByMechanicAndTier = regularProfiles
            .flatMap { profile -> catalog.setsFor(profile).map { set -> Triple(profile.mechanic, set.setTier, set.speciesId) } }
            .groupBy({ it.first to it.second }, { it.third })
        speciesByMechanicAndTier.forEach { (category, species) ->
            assertTrue(
                species.distinct().size >= MINIMUM_SPECIES_PER_MECHANIC_TIER,
                "$category has only ${species.distinct().size} species",
            )
        }

        regularProfiles.forEach { profile ->
            val sets = catalog.setsFor(profile)
            assertTrue(sets.map(TowerPokemonSet::speciesId).distinct().size >= MINIMUM_SPECIES_PER_MECHANIC_TIER, profile.profileId)
            assertTrue(sets.mapNotNull(TowerPokemonSet::heldItemId).distinct().size >= 6, profile.profileId)
            sets.forEach { set -> assertMechanicShape(profile.mechanic!!, set) }
        }

        val lowSets = uniqueSets.filter { it.setTier == 1 }
        val highSets = uniqueSets.filter { it.setTier == 2 }
        assertTrue(lowSets.size >= MINIMUM_SPECIES_PER_MECHANIC_TIER * MajorBattleMechanic.entries.size)
        assertTrue(highSets.size >= MINIMUM_SPECIES_PER_MECHANIC_TIER * MajorBattleMechanic.entries.size)
        lowSets.forEach { set ->
            assertEquals(1, set.setTier)
            assertEquals(TowerStatSpread(15, 15, 15, 15, 15, 15), set.ivs)
            assertEquals(0, set.evs.total)
        }
        highSets.forEach { set ->
            assertEquals(2, set.setTier)
            assertEquals(TowerStatSpread(20, 20, 20, 20, 20, 20), set.ivs)
            assertEquals(252, set.evs.total)
            assertEquals(1, set.evs.nonZeroStatCount())
        }

        profiles.filter { it.opponentKind != TowerOpponentKind.REGULAR }.forEach { boss ->
            assertEquals(4, boss.aiSkill)
        }
    }

    @Test
    fun `approved trainer profile names exist in both bundled languages`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        val profiles = approvedProfiles(bundledCatalog())
        val englishNames = profiles.map { english[it.displayNameKey].asString }
        val koreanNames = profiles.map { korean[it.displayNameKey].asString }
        assertTrue(englishNames.distinct().size >= MINIMUM_TRAINERS_PER_CATEGORY)
        assertTrue(koreanNames.distinct().size >= MINIMUM_TRAINERS_PER_CATEGORY)

        EXPECTED_NAMES.forEach { (profileId, names) ->
            val key = "trainer.cobblemon_more_battle_content.$profileId"
            assertEquals(names.first, english[key].asString)
            assertEquals(names.second, korean[key].asString)
        }
    }

    @Test
    fun `every bundled ability and move belongs to its exact Cobblemon species or form`() {
        val catalog = bundledCatalog()
        val sets = approvedProfiles(catalog)
            .filter { it.opponentKind == TowerOpponentKind.REGULAR }
            .flatMap(catalog::setsFor)
            .distinctBy(TowerPokemonSet::setId)

        cobblemonJar().use { jar ->
            val speciesEntries = jar.entries().asSequence()
                .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }

            sets.forEach { set ->
                val speciesName = set.speciesId.substringAfter(':')
                val entry = requireNotNull(speciesEntries[speciesName]) { "Missing Cobblemon species JSON for ${set.speciesId}" }
                val root = jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                val form = set.formId?.let { formId ->
                    root.getAsJsonArray("forms")
                        ?.map { it.asJsonObject }
                        ?.firstOrNull { it["name"].asString.equals(formId, ignoreCase = true) }
                }
                val abilityOwner = form?.takeIf { it.has("abilities") } ?: root
                val abilities = abilityOwner.getAsJsonArray("abilities")
                    .map { it.asString.removePrefix("h:") }
                    .toSet()
                val learnset = buildSet {
                    root.getAsJsonArray("moves")?.forEach { add(it.asString.substringAfter(':')) }
                    form?.getAsJsonArray("moves")?.forEach { add(it.asString.substringAfter(':')) }
                }

                set.abilityId?.let { abilityId ->
                    assertTrue(abilityId.substringAfter(':') in abilities, "${set.setId} cannot have $abilityId; allowed=$abilities")
                }
                set.moves.forEach { moveId ->
                    assertTrue(moveId.substringAfter(':') in learnset, "${set.setId} cannot learn $moveId")
                }
            }
        }
    }

    private fun bundledCatalog(): TowerOpponentCatalog {
        val loaded = TowerOpponentCatalogLoader.loadSeparated(
            trainerFragments = fragmentReaders(TRAINER_DIRECTORY),
            poolFragments = fragmentReaders(POOL_DIRECTORY),
            encounterFragments = fragmentReaders(ENCOUNTER_DIRECTORY),
            pokemonSetFragments = fragmentReaders(POKEMON_SET_DIRECTORY),
        )
        return (loaded as TowerOpponentCatalogLoadResult.Loaded).catalog
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

    private fun approvedProfiles(catalog: TowerOpponentCatalog): List<TowerOpponentProfile> =
        MajorBattleMechanic.entries.flatMap { mechanic ->
            TowerBattleFormat.entries.flatMap { format ->
                TowerRank.entries.flatMap { rank ->
                    TowerOpponentKind.entries.flatMap { kind ->
                        catalog.profilesFor(rank, format, kind, mechanic)
                    }
                }
            }
        }.distinctBy { profile ->
            listOf(profile.profileId, profile.rankIds, profile.format, profile.opponentKind, profile.mechanic, profile.theme)
        }

    private fun assertMechanicShape(mechanic: MajorBattleMechanic, set: TowerPokemonSet) {
        when (mechanic) {
            MajorBattleMechanic.MEGA -> {
                assertTrue(set.heldItemId!!.startsWith("mega_showdown:"), set.setId)
                assertEquals(null, set.teraType)
                assertEquals(null, set.dmaxLevel)
                assertEquals(null, set.gmaxFactor)
            }
            MajorBattleMechanic.DYNAMAX -> {
                assertEquals(null, set.teraType)
                assertEquals(10, set.dmaxLevel)
                assertNotNull(set.gmaxFactor)
            }
            MajorBattleMechanic.TERA -> {
                assertTrue(set.teraType in TowerPokemonSet.SUPPORTED_TERA_TYPES, set.setId)
                assertEquals(null, set.dmaxLevel)
                assertEquals(null, set.gmaxFactor)
            }
        }
    }

    private fun TowerStatSpread.nonZeroStatCount(): Int =
        listOf(hp, attack, defense, specialAttack, specialDefense, speed).count { it != 0 }

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
        const val MINIMUM_TRAINERS_PER_CATEGORY = 50
        const val MINIMUM_SPECIES_PER_MECHANIC_TIER = 50
        const val EXPECTED_PROFILE_CATEGORY_COUNT = 24
        const val TRAINER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/trainers"
        const val POOL_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pools"
        const val ENCOUNTER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/encounters"
        const val POKEMON_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pokemon-sets"

        val EXPECTED_NAMES = mapOf(
            "mega_single_regular_low" to ("Liam" to "민준"),
            "mega_single_regular_high" to ("Emma" to "서연"),
            "mega_single_tier_boss" to ("Noah" to "지훈"),
            "mega_double_regular_low" to ("Olivia" to "유나"),
            "mega_double_regular_high" to ("Ethan" to "현우"),
            "mega_double_tier_boss" to ("Sophie" to "수빈"),
            "dynamax_single_regular_low" to ("Lucas" to "준호"),
            "dynamax_single_regular_high" to ("Mia" to "하린"),
            "dynamax_single_tier_boss" to ("Owen" to "도윤"),
            "dynamax_double_regular_low" to ("Chloe" to "지민"),
            "dynamax_double_regular_high" to ("Mason" to "태윤"),
            "dynamax_double_tier_boss" to ("Lily" to "예린"),
            "tera_single_regular_low" to ("Ryan" to "승현"),
            "tera_single_regular_high" to ("Grace" to "나연"),
            "tera_single_tier_boss" to ("Leo" to "시우"),
            "tera_double_regular_low" to ("Hannah" to "다은"),
            "tera_double_regular_high" to ("Jack" to "건우"),
            "tera_double_tier_boss" to ("Ella" to "채원"),
        )
    }
}
