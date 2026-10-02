package jbro.cobblemon.mcc.internal.tower.opponent

import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerOpponentKind
import jbro.cobblemon.mcc.internal.tower.TowerStreakStage
import jbro.cobblemon.mcc.internal.tower.TowerProgress
import jbro.cobblemon.mcc.internal.tower.TowerProgression
import jbro.cobblemon.mcc.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.mcc.internal.tower.TowerLegendaryCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourceTest {
    @Test
    fun `every bundled trainer wears an RCT Trainers+ skin instead of the challenger's hologram`() {
        val catalog = bundledCatalog()
        val profiles = TowerStreakStage.entries.flatMap { stage ->
            TowerBattleFormat.entries.flatMap { format -> TowerOpponentKind.entries.flatMap { kind -> catalog.profilesFor(stage, format, kind) } }
        }
        assertTrue(profiles.isNotEmpty())
        profiles.forEach { profile ->
            val skin = profile.appearance?.texture
            assertTrue(skin != null && skin.startsWith("rctmod:textures/trainers/single/") && skin.endsWith(".png"), "${profile.profileId} wears $skin")
        }
    }

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
                        // Bosses are the Tower Aces and the Champions (see the boss schedule test).
                        val minimum = if (kind == TowerOpponentKind.REGULAR) MINIMUM_REGULAR_TRAINERS_PER_CATEGORY else CHAMPIONS.size
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

        assertEquals(EXPECTED_DISTINCT_TRAINERS, profiles.distinctBy(TowerOpponentProfile::profileId).size)
        // The Champions bring their own rosters; everything below is about the pool-driven trainers.
        val distinctTrainers = profiles.filterNot { it.fixedRoster }.distinctBy(TowerOpponentProfile::profileId)
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
        categories.filterValues { trainers -> trainers.first().opponentKind == TowerOpponentKind.REGULAR }.forEach { (category, trainers) ->
            assertTrue(trainers.size >= MINIMUM_REGULAR_TRAINERS_PER_CATEGORY, category.toString())
            assertEquals(1, trainers.map { it.setIds.sorted() }.distinct().size, "Trainers in $category must share its rule-driven pool")
        }
        profiles.filterNot { it.fixedRoster }.forEach { profile ->
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
        // Training climbs with the stage: tier 1 IV 15 and no EVs, tier 2 IV 20 and 252, tier 3 IV 25 and 384, tier 4
        // IV 31 and 508. Champions (tier 9) are always fully trained.
        val training = mapOf(1 to (15 to 0), 2 to (20 to 252), 3 to (25 to 384), 4 to (31 to 508), 9 to (31 to 508))
        uniqueSets.forEach { set ->
            val (iv, evs) = training.getValue(set.setTier)
            assertEquals(TowerStatSpread(iv, iv, iv, iv, iv, iv), set.ivs, set.setId)
            assertEquals(evs, set.evs.total, set.setId)
        }
        highSets.forEach { set -> assertEquals(1, set.evs.nonZeroStatCount(), set.setId) }

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
    fun `every 5th win is a Champion and the Tower Aces fight among the advanced and pro regulars`() {
        val catalog = bundledCatalog()
        TowerBattleFormat.entries.forEach { format ->
            MajorBattleMechanic.entries.forEach { mechanic ->
                fun profiles(stage: TowerStreakStage, kind: TowerOpponentKind) =
                    catalog.profilesFor(stage, format, kind, mechanic).map { it.profileId }.toSet()
                assertEquals(CHAMPIONS.keys, profiles(TowerStreakStage.INTRODUCTORY, TowerOpponentKind.TIER_BOSS))
                assertEquals(CHAMPIONS.keys, profiles(TowerStreakStage.PRACTICAL, TowerOpponentKind.TIER_BOSS))
                assertEquals(CHAMPIONS.keys, profiles(TowerStreakStage.ADVANCED, TowerOpponentKind.TIER_BOSS))
                assertEquals(CHAMPIONS.keys, profiles(TowerStreakStage.PRO, TowerOpponentKind.MASTER_BALL_BOSS))
                fun aces(stage: TowerStreakStage) = catalog.profilesFor(stage, format, TowerOpponentKind.REGULAR, mechanic)
                    .count { it.isTowerAce() }
                assertEquals(0, aces(TowerStreakStage.INTRODUCTORY))
                assertEquals(0, aces(TowerStreakStage.PRACTICAL))
                assertEquals(ACES_PER_CATEGORY, aces(TowerStreakStage.ADVANCED))
                assertEquals(ACES_PER_CATEGORY, aces(TowerStreakStage.PRO))
                CHAMPIONS.keys.forEach { champion ->
                    assertTrue(catalog.profilesFor(TowerStreakStage.PRO, format, TowerOpponentKind.REGULAR, mechanic).none { it.profileId == champion })
                }
            }
        }
    }

    @Test
    fun `a Champion brings the ace, one Mega Stone and as many legendaries as the challenger, main line first`() {
        val catalog = bundledCatalog()
        val counts = listOf(TowerLegendaryCount(0, 0), TowerLegendaryCount(1, 0), TowerLegendaryCount(0, 1), TowerLegendaryCount(1, 1))
        TowerBattleFormat.entries.forEach { format ->
            MajorBattleMechanic.entries.forEach { mechanic ->
                counts.forEach { challenger ->
                    CHAMPIONS.forEach { (champion, ace) ->
                        val profile = catalog.profilesFor(TowerStreakStage.PRO, format, TowerOpponentKind.MASTER_BALL_BOSS, mechanic)
                            .single { it.profileId == champion }
                        val variety = HashSet<String>()
                        repeat(12) { seed ->
                            val result = TowerOpponentSelector(catalog, SeededRandom(seed.toLong())).select(TowerStreakStage.PRO, format,
                                TowerOpponentKind.MASTER_BALL_BOSS, mechanic, excludedProfileIds = CHAMPIONS.keys - champion,
                                legendaryClassAllowed = true, championBoss = true, challengerLegendaries = challenger)
                                as TowerOpponentSelectionResult.Selected
                            val team = result.team
                            assertEquals(champion, result.profile.profileId)
                            assertEquals(format.selectionSize, team.size)
                            assertEquals(team.size, team.map { it.speciesId }.distinct().size)
                            assertEquals(team.size, team.mapNotNull { it.heldItemId }.distinct().size, "item clause $team")
                            assertTrue(team.all { it.setId.startsWith("${champion}_${mechanic.id}_") }, "$champion $mechanic $team")
                            assertTrue(team.any { it.speciesId == ace }, "$champion leaves out $ace")
                            val legends = team.filter { TowerLegendaryClassPolicy.isLegendaryClass(it.speciesId) }.map { it.speciesId }
                            assertEquals(challenger.total, legends.size, "$champion against $challenger: $legends")
                            val main = profile.legendLines.single { it.main }
                            if (challenger.total > 0) assertTrue(main.speciesId in legends, "$champion leaves out ${main.speciesId}")
                            profile.legendLines.filter { it.speciesId in legends }.forEach { line ->
                                assertTrue(team.none { it.speciesId == line.replaces }, "${line.speciesId} plays beside ${line.replaces}")
                            }
                            team.forEach { set ->
                                assertEquals(TowerStatSpread(31, 31, 31, 31, 31, 31), set.ivs)
                                assertTrue(set.evs.total >= 508, set.setId)
                            }
                            if (mechanic == MajorBattleMechanic.MEGA) {
                                val stone = Regex("^mega_showdown:[a-z_]+ite(_[xyz])?$")
                                val stones = team.count { it.heldItemId!!.matches(stone) }
                                // One Mega Stone, unless the ace and the legendaries fill every seat and the ace cannot Mega Evolve.
                                val aceMegas = catalog.setsFor(profile).any { it.speciesId == ace && it.heldItemId?.matches(stone) == true }
                                val seatLeft = format.selectionSize > 1 + challenger.total
                                if (seatLeft || aceMegas) assertEquals(1, stones, "$champion $format $challenger ${team.map { it.setId }}") else assertTrue(stones <= 1, "$team")
                            } else {
                                team.forEach { assertMechanicShape(mechanic, it) }
                            }
                            variety += team.map { it.setId }
                        }
                        // Several sets per member: the same Champion does not field the same six sets every time.
                        assertTrue(variety.size > format.selectionSize, "$champion $mechanic always fields $variety")
                    }
                }
            }
        }
        assertEquals(true, catalog.allSets().single { it.setId == "champion_blue_dynamax_blastoise_1" }.gmaxFactor)
    }

    @Test
    fun `a regular trainer answers the challenger's legendaries group for group`() {
        val catalog = bundledCatalog()
        listOf(TowerLegendaryCount(0, 0), TowerLegendaryCount(1, 0), TowerLegendaryCount(0, 1), TowerLegendaryCount(1, 1)).forEach { challenger ->
            repeat(8) { seed ->
                val result = TowerOpponentSelector(catalog, SeededRandom(seed.toLong())).select(TowerStreakStage.PRO, TowerBattleFormat.SINGLE,
                    TowerOpponentKind.REGULAR, MajorBattleMechanic.TERA, legendaryClassAllowed = true, challengerLegendaries = challenger)
                    as TowerOpponentSelectionResult.Selected
                assertEquals(challenger, TowerLegendaryClassPolicy.count(result.team.map { it.speciesId }), "${result.team}")
            }
        }
    }

    private class SeededRandom(seed: Long) : TowerOpponentRandom {
        private val random = kotlin.random.Random(seed)
        override fun nextLong(bound: Long): Long = random.nextLong(bound)
        override fun nextInt(bound: Int): Int = random.nextInt(bound)
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
        assertEquals(CHAMPIONS.keys, bosses.map(TowerOpponentProfile::profileId).toSet())
        val aces = profiles.filter { it.isTowerAce() }.distinctBy(TowerOpponentProfile::profileId)
        assertEquals(ACES_PER_CATEGORY * 6, aces.size)
        (bosses + aces).forEach { trainer ->
            if (trainer.profileId == CHAMPION_CLASS_WITHOUT_TITLE) return@forEach
            val (en, ko) = if (trainer.fixedRoster) "Champion " to "챔피언 " else "Tower Ace " to "타워 에이스 "
            assertTrue(english[trainer.displayNameKey].asString.startsWith(en) && korean[trainer.displayNameKey].asString.startsWith(ko), trainer.profileId)
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
        "/assets/more_cobblemon_contents_battle_tower/lang/$code.json",
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
        /** 96 regulars, the 24 Tower Aces and the three Champions. */
        const val ACES_PER_CATEGORY = 4

        /** Trainers 097 to 120, the Tower Aces: four per gimmick and format. */
        fun TowerOpponentProfile.isTowerAce(): Boolean = profileId.removePrefix("trainer_").toIntOrNull() in 97..120
        const val EXPECTED_DISTINCT_TRAINERS = 131
        /** Each Champion, by the ace every one of their teams carries. */
        val CHAMPIONS = mapOf(
            "champion_blue" to "cobblemon:blastoise",
            "champion_lance" to "cobblemon:dragonite",
            "champion_cynthia" to "cobblemon:garchomp",
            "champion_steven" to "cobblemon:metagross",
            "champion_wallace" to "cobblemon:milotic",
            "champion_alder" to "cobblemon:volcarona",
            "champion_iris" to "cobblemon:haxorus",
            "champion_diantha" to "cobblemon:gardevoir",
            "champion_geeta" to "cobblemon:glimmora",
            "champion_nemona" to "cobblemon:pawmot",
            "champion_n" to "cobblemon:zoroark",
        )

        /** N is no Champion, though he fights as one: his name goes without the title. */
        const val CHAMPION_CLASS_WITHOUT_TITLE = "champion_n"
        const val SIGNATURE_SPECIES_PER_TRAINER = 3
        const val MINIMUM_DISTINCT_SIGNATURE_GROUPS = 60
        const val MINIMUM_SPECIES_PER_MECHANIC_TIER = 50
        // Per mechanic and format: introductory, practical, advanced and pro regulars, a tier boss per stage up to
        // advanced, and the Master Ball boss.
        const val EXPECTED_PROFILE_CATEGORY_COUNT = 48
        const val TRAINER_DIRECTORY = "/data/more_cobblemon_contents/mcc-battle-tower/trainers"
        const val POOL_DIRECTORY = "/data/more_cobblemon_contents/mcc-battle-tower/pools"
        const val ENCOUNTER_DIRECTORY = "/data/more_cobblemon_contents/mcc-battle-tower/encounters"
        const val POKEMON_SET_DIRECTORY = "/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets"
    }
}
