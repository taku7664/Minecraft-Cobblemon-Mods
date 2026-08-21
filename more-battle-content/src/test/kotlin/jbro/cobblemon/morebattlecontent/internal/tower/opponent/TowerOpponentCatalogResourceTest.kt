package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import com.google.gson.JsonParser
import java.io.InputStreamReader
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
    fun `bundled catalog contains the approved schema three profiles and sets`() {
        val catalog = bundledCatalog()
        val profiles = approvedProfiles(catalog)

        assertTrue(profiles.map(TowerOpponentProfile::profileId).toSet().containsAll(EXPECTED_PROFILE_IDS))
        assertEquals(72, profiles.size)
        assertEquals(18, profiles.groupBy { listOf(it.rankIds, it.format, it.opponentKind, it.mechanic, it.theme) }.size)
        profiles.groupBy { listOf(it.rankIds, it.format, it.opponentKind, it.mechanic, it.theme) }
            .forEach { (category, variants) -> assertEquals(4, variants.size, category.toString()) }
        profiles.forEach { profile ->
            assertEquals("trainer.cobblemon_more_battle_content.${profile.profileId}", profile.displayNameKey)
            assertEquals(12, profile.setIds.size)
            assertEquals(12, profile.setIds.distinct().size)
        }

        val regularProfiles = profiles.filter { it.opponentKind == TowerOpponentKind.REGULAR }
        val uniqueSets = regularProfiles.flatMap(catalog::setsFor).distinctBy(TowerPokemonSet::setId)
        assertEquals(72, uniqueSets.size)
        assertEquals(72, uniqueSets.map(TowerPokemonSet::setId).distinct().size)

        regularProfiles.forEach { profile ->
            val sets = catalog.setsFor(profile)
            assertEquals(12, sets.map(TowerPokemonSet::speciesId).distinct().size, profile.profileId)
            assertTrue(sets.mapNotNull(TowerPokemonSet::heldItemId).distinct().size >= 6, profile.profileId)
            sets.forEach { set -> assertMechanicShape(profile.mechanic!!, set) }
        }

        val lowSets = uniqueSets.filter { it.setTier == 1 }
        val highSets = uniqueSets.filter { it.setTier == 2 }
        assertEquals(36, lowSets.size)
        assertEquals(36, highSets.size)
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

        EXPECTED_POOL_SPECIES.forEach { (profileId, species) ->
            val profile = profiles.single { it.profileId == profileId }
            assertTrue(
                catalog.setsFor(profile).map { it.speciesId.substringAfter(':') }.containsAll(species),
                profileId,
            )
        }
        profiles.filter { it.opponentKind == TowerOpponentKind.TIER_BOSS }.forEach { boss ->
            val high = profiles.first {
                it.opponentKind == TowerOpponentKind.REGULAR &&
                    it.mechanic == boss.mechanic && it.format == boss.format && "_high" in it.profileId
            }
            assertEquals(high.setIds, boss.setIds, boss.profileId)
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
        assertEquals(profiles.size, englishNames.distinct().size)
        assertEquals(profiles.size, koreanNames.distinct().size)

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
        val stream = javaClass.getResourceAsStream(CATALOG_PATH)
        assertNotNull(stream, "Missing bundled opponent catalog")
        val loaded = stream!!.reader().use(TowerOpponentCatalogLoader::load)
        return (loaded as TowerOpponentCatalogLoadResult.Loaded).catalog
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
        }.distinctBy(TowerOpponentProfile::profileId)

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
        const val CATALOG_PATH =
            "/data/cobblemon_more_battle_content/battle_tower/opponents/mbc_core.json"

        val EXPECTED_PROFILE_IDS = setOf(
            "mega_single_regular_low", "mega_single_regular_high", "mega_single_tier_boss",
            "mega_double_regular_low", "mega_double_regular_high", "mega_double_tier_boss",
            "dynamax_single_regular_low", "dynamax_single_regular_high", "dynamax_single_tier_boss",
            "dynamax_double_regular_low", "dynamax_double_regular_high", "dynamax_double_tier_boss",
            "tera_single_regular_low", "tera_single_regular_high", "tera_single_tier_boss",
            "tera_double_regular_low", "tera_double_regular_high", "tera_double_tier_boss",
        )

        val EXPECTED_POOL_SPECIES = mapOf(
            "mega_single_regular_low" to listOf("absol", "ampharos", "banette", "abomasnow", "audino", "pidgeot"),
            "mega_single_regular_high" to listOf("garchomp", "gengar", "metagross", "salamence", "tyranitar", "scizor"),
            "mega_double_regular_low" to listOf("manectric", "altaria", "camerupt", "gallade", "slowbro", "kangaskhan"),
            "mega_double_regular_high" to listOf("charizard", "venusaur", "blastoise", "mawile", "lucario", "aerodactyl"),
            "dynamax_single_regular_low" to listOf("corviknight", "drednaw", "orbeetle", "sandaconda", "toxtricity", "alcremie"),
            "dynamax_single_regular_high" to listOf("dragapult", "excadrill", "mimikyu", "hydreigon", "togekiss", "conkeldurr"),
            "dynamax_double_regular_low" to listOf("pelipper", "ludicolo", "arcanine", "gastrodon", "raichu", "ferrothorn"),
            "dynamax_double_regular_high" to listOf("coalossal", "rillaboom", "indeedee", "duraludon", "copperajah", "snorlax"),
            "tera_single_regular_low" to listOf("meowscarada", "ceruledge", "clodsire", "kilowattrel", "baxcalibur", "tinkaton"),
            "tera_single_regular_high" to listOf("kingambit", "gholdengo", "dragonite", "volcarona", "garganacl", "azumarill"),
            "tera_double_regular_low" to listOf("murkrow", "garchomp", "armarouge", "farigiraf", "amoonguss", "sylveon"),
            "tera_double_regular_high" to listOf("fluttermane", "ironhands", "incineroar", "rillaboom", "gholdengo", "dragonite"),
        )

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
