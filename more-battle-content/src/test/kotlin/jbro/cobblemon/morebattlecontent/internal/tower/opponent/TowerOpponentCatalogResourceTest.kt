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
import jbro.cobblemon.morebattlecontent.internal.tower.TowerStreakStage
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgress
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgression
import jbro.cobblemon.morebattlecontent.internal.tower.TowerLegendaryClassPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceTest {
    @Test
    fun `bundled catalog can prepare every streak stage with legendary class off and on`() {
        val catalog = bundledCatalog()
        val sampleStreaks = listOf(0, 4, 5, 9, 10, 14, 15, 19, 20, 24, 25)

        TowerBattleFormat.entries.forEach { format ->
            MajorBattleMechanic.entries.forEach { mechanic ->
                sampleStreaks.forEach { streak ->
                    val progress = TowerProgress(format, streak, streak)
                    val selector = TowerOpponentSelector(catalog)
                    listOf(false, true).forEach { allowed ->
                        val result = selector.select(
                            stage = progress.nextStage,
                            format = format,
                            opponentKind = TowerProgression.nextOpponent(progress),
                            mechanic = mechanic,
                            legendaryClassAllowed = allowed,
                        ) as TowerOpponentSelectionResult.Selected
                        assertEquals(format.selectionSize, result.team.size)
                        if (!allowed) {
                            assertTrue(
                                result.team.none { TowerLegendaryClassPolicy.isLegendaryClass(it.speciesId) },
                                "$format $mechanic streak=$streak allowed=false",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every reachable tower battle has a broad regular pool and dedicated bosses`() {
        val catalog = bundledCatalog()

        TowerStreakStage.entries.forEach { stage ->
            val reachableKinds = buildList {
                add(TowerOpponentKind.REGULAR)
                if (stage == TowerStreakStage.PRO) {
                    add(TowerOpponentKind.MASTER_BALL_BOSS)
                } else {
                    add(TowerOpponentKind.TIER_BOSS)
                }
            }
            TowerBattleFormat.entries.forEach { format ->
                MajorBattleMechanic.entries.forEach { mechanic ->
                    reachableKinds.forEach { kind ->
                        val profiles = catalog.profilesFor(stage, format, kind, mechanic)
                        val minimum = if (kind == TowerOpponentKind.REGULAR) {
                            MINIMUM_REGULAR_TRAINERS_PER_CATEGORY
                        } else {
                            MINIMUM_BOSSES_PER_CATEGORY
                        }
                        assertTrue(profiles.size >= minimum, "$stage $format $kind $mechanic has only ${profiles.size} eligible trainers")
                    }
                }
            }
        }
    }

    @Test
    fun `bundled catalog provides many distinct tactical trainers and broad species pools per category`() {
        val catalog = bundledCatalog()
        val profiles = approvedProfiles(catalog)

        val distinctTrainers = profiles.distinctBy(TowerOpponentProfile::profileId)
        assertEquals(EXPECTED_DISTINCT_TRAINERS, distinctTrainers.size)
        assertTrue(distinctTrainers.map(TowerOpponentProfile::profileId).toSet().containsAll(setOf("trainer_001", "trainer_096")))
        assertEquals(TowerTrainerStyle.entries.toSet(), distinctTrainers.map(TowerOpponentProfile::teamStyle).toSet())
        assertTrue(distinctTrainers.all { it.signatureSpeciesIds.size == SIGNATURE_SPECIES_PER_TRAINER })
        assertTrue(distinctTrainers.map { it.signatureSpeciesIds }.distinct().size >= MINIMUM_DISTINCT_SIGNATURE_GROUPS)
        distinctTrainers.forEach { trainer ->
            assertTrue(
                trainer.teamStyle == TowerTrainerStyle.BALANCED || catalog.setsFor(trainer).any(trainer.teamStyle::matches),
                "${trainer.profileId} has no ${trainer.teamStyle.serializedId} signature set",
            )
        }
        val categories = profiles.groupBy {
            listOf(it.stageIds, it.format, it.opponentKind, it.mechanic, it.theme)
        }
        assertEquals(EXPECTED_PROFILE_CATEGORY_COUNT, categories.size)
        categories.forEach { (category, trainers) ->
            val minimum = if (trainers.first().opponentKind == TowerOpponentKind.REGULAR) {
                MINIMUM_REGULAR_TRAINERS_PER_CATEGORY
            } else {
                MINIMUM_BOSSES_PER_CATEGORY
            }
            assertTrue(trainers.size >= minimum, category.toString())
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
    fun `every bundled tactical style puts a signature set on its selected team`() {
        val catalog = bundledCatalog()
        val categories = approvedProfiles(catalog).groupBy {
            listOf(it.stageIds, it.format, it.opponentKind, it.mechanic, it.theme)
        }

        categories.values.forEach { profiles ->
            profiles.distinctBy(TowerOpponentProfile::teamStyle).forEach { target ->
                if (target.teamStyle == TowerTrainerStyle.BALANCED) return@forEach
                val result = TowerOpponentSelector(catalog).select(
                    stage = target.stageIds.first(),
                    format = target.format,
                    opponentKind = target.opponentKind,
                    mechanic = requireNotNull(target.mechanic),
                    excludedProfileIds = profiles.map(TowerOpponentProfile::profileId).filterNot { it == target.profileId }.toSet(),
                    legendaryClassAllowed = true,
                ) as TowerOpponentSelectionResult.Selected

                assertEquals(target.profileId, result.profile.profileId)
                assertTrue(
                    result.team.any(target.teamStyle::matches),
                    "${target.profileId} selected no ${target.teamStyle.serializedId} signature",
                )
                assertTrue(
                    result.team.any { it.speciesId in target.signatureSpeciesIds },
                    "${target.profileId} selected none of its signature species ${target.signatureSpeciesIds}",
                )
            }
        }
    }

    @Test
    fun `approved trainer profile names exist in both bundled languages`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        val profiles = approvedProfiles(bundledCatalog())
        val englishNames = profiles.map { english[it.displayNameKey].asString }
        val koreanNames = profiles.map { korean[it.displayNameKey].asString }
        assertEquals(EXPECTED_DISTINCT_TRAINERS, englishNames.distinct().size)
        assertEquals(EXPECTED_DISTINCT_TRAINERS, koreanNames.distinct().size)
        val bosses = profiles.filter { it.opponentKind != TowerOpponentKind.REGULAR }.distinctBy(TowerOpponentProfile::profileId)
        assertEquals(EXPECTED_DEDICATED_BOSSES, bosses.size)
        assertTrue(bosses.all { english[it.displayNameKey].asString.startsWith("Tower Ace ") })
        assertTrue(bosses.all { korean[it.displayNameKey].asString.startsWith("타워 에이스 ") })
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
                TowerStreakStage.entries.flatMap { stage ->
                    TowerOpponentKind.entries.flatMap { kind ->
                        catalog.profilesFor(stage, format, kind, mechanic)
                    }
                }
            }
        }.distinctBy { profile ->
            listOf(profile.profileId, profile.stageIds, profile.format, profile.opponentKind, profile.mechanic, profile.theme)
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
        const val MINIMUM_REGULAR_TRAINERS_PER_CATEGORY = 84
        const val MINIMUM_BOSSES_PER_CATEGORY = 4
        const val EXPECTED_DISTINCT_TRAINERS = 120
        const val EXPECTED_DEDICATED_BOSSES = 24
        const val SIGNATURE_SPECIES_PER_TRAINER = 3
        const val MINIMUM_DISTINCT_SIGNATURE_GROUPS = 60
        const val MINIMUM_SPECIES_PER_MECHANIC_TIER = 50
        const val EXPECTED_PROFILE_CATEGORY_COUNT = 24
        const val TRAINER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/trainers"
        const val POOL_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pools"
        const val ENCOUNTER_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/encounters"
        const val POKEMON_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-tower/pokemon-sets"
    }
}
