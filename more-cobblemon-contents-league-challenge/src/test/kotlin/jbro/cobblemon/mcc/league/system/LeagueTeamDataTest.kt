package jbro.cobblemon.mcc.league.system

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Paths
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The bundled Sinnoh League in two difficulties.
 * - Normal reproduces the Platinum first battles (pret/pokeplatinum trainer data): species, level, moves, held item,
 *   the fixed IVs and the nature, ability and gender the game derives from each trainer, with no EVs.
 * - Hard opens after the normal Champion: six fully trained Pokemon per trainer, each team led by a Mega ace.
 * Every value must be one Cobblemon or Mega Showdown knows, since Cobblemon's property parser silently drops the rest.
 */
class LeagueTeamDataTest {
    private val ns = "more_cobblemon_contents_league_challenge"
    private val gyms = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner")
    private val finals = listOf("aaron", "bertha", "flint", "lucian", "cynthia")
    private val normal = gyms + finals
    private val hard = normal.map { "${it}_hard" }
    private val stats = listOf("hp", "attack", "defence", "special_attack", "special_defence", "speed")
    private val natures = setOf("hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile", "relaxed", "impish", "lax",
        "timid", "hasty", "serious", "jolly", "naive", "modest", "mild", "quiet", "bashful", "rash", "calm", "gentle", "sassy",
        "careful", "quirky")

    private fun json(directory: String, name: String) = JsonParser.parseString(
        javaClass.getResourceAsStream("/data/$ns/league-challenge/$directory/$name.json")!!.bufferedReader().use { it.readText() },
    ).asJsonObject

    private fun team(name: String): List<String> = json("teams", name).getAsJsonArray("pokemon").map { it.asString }

    private fun fields(line: String): Map<String, String> =
        line.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun level(line: String) = fields(line).getValue("level").toInt()

    @Test
    fun `every team member names only species, moves, abilities, natures and items the game has`() {
        (normal + hard).forEach { name ->
            team(name).forEach { line ->
                val species = line.substringBefore(' ')
                val fields = fields(line)
                val at = "$name: $line"
                assertTrue(species in data.species, "unknown species in $at")
                val moves = fields.getValue("moves").split(',')
                assertTrue(moves.size in 1..4 && moves.distinct().size == moves.size && moves.all { it in data.moves }, "bad moves in $at")
                assertTrue(fields.getValue("ability") in data.abilities, "unknown ability in $at")
                assertTrue(fields.getValue("nature") in natures, "unknown nature in $at")
                fields["gender"]?.let { assertTrue(it == "male" || it == "female", "bad gender in $at") }
                fields["held_item"]?.let { assertTrue(it in data.items, "unknown item in $at") }
                val evs = stats.map { fields.getValue("${it}_ev").toInt() }
                assertTrue(evs.all { it in 0..252 } && evs.sum() <= 510, "bad EVs in $at")
                stats.forEach { assertTrue(fields.getValue("${it}_iv").toInt() in 0..31, "bad $it IV in $at") }
            }
        }
    }

    @Test
    fun `Cobblemon's parser keeps every value of every team member`() {
        (normal + hard).forEach { name ->
            team(name).forEach { line ->
                val fields = fields(line)
                val properties = PokemonProperties.parse(line)
                val at = "$name: $line"
                assertEquals(line.substringBefore(' '), properties.species, at)
                assertEquals(fields.getValue("level").toInt(), properties.level, at)
                assertEquals(fields.getValue("moves").split(','), properties.moves, at)
                assertEquals(fields.getValue("nature"), properties.nature?.substringAfter(':'), at)
                assertEquals(fields.getValue("ability"), properties.ability?.substringAfter(':'), at)
                assertEquals(fields["held_item"], properties.heldItem, at)
                fields["gender"]?.let { assertEquals(it, properties.gender?.name?.lowercase(), at) }
                val ivs = requireNotNull(properties.ivs) { at }
                val evs = requireNotNull(properties.evs) { at }
                Stats.PERMANENT.forEach { stat ->
                    val key = stat.toString().lowercase()
                    assertEquals(fields.getValue("${key}_iv").toInt(), ivs[stat], "$key IV $at")
                    assertEquals(fields.getValue("${key}_ev").toInt(), evs[stat], "$key EV $at")
                }
            }
        }
    }

    @Test
    fun `normal teams are the Platinum first battles`() {
        normal.forEach { name -> team(name).forEach { line -> stats.forEach { assertEquals("0", fields(line).getValue("${it}_ev"), line) } } }
        val garchomp = fields(team("cynthia").last())
        assertEquals("62", garchomp.getValue("level"))
        assertEquals("cobblemon:sitrus_berry", garchomp.getValue("held_item"))
        assertEquals("dragonrush,earthquake,flamethrower,gigaimpact", garchomp.getValue("moves"))
        assertEquals("30", garchomp.getValue("speed_iv"))
        // Volkner's Electivire has an IV scale past the maximum, so the game rolls its IVs from the trainer's seed.
        val electivire = fields(team("volkner").last())
        assertEquals(listOf("4", "3", "29", "18", "15", "19"), stats.map { electivire.getValue("${it}_iv") })
        // Platinum always gives trainers' Pokemon their first ability.
        assertEquals("innerfocus", fields(team("candice").first()).getValue("ability"))
        assertEquals(listOf(2, 3, 3), team("roark").map { fields(it).getValue("moves").split(',').size })
    }

    @Test
    fun `hard teams are six trained Pokemon behind one Mega ace`() {
        hard.forEach { name ->
            val members = team(name)
            assertEquals(6, members.size, name)
            val ace = members.last()
            val stones = members.filter { fields(it)["held_item"]?.let(data.megaStones::containsKey) == true }
            assertEquals(listOf(ace), stones, "$name needs exactly one Mega Stone, on its ace")
            assertEquals(ace.substringBefore(' '), data.megaStones.getValue(fields(ace).getValue("held_item")), "$name ace holds another's stone")
            assertEquals(members.maxOf(::level), level(ace), "$name ace is not its highest level")
            assertEquals("MEGA", json("challenges", name)["mechanic"].asString, name)
            assertEquals(5, json("trainers", name)["ai_skill"].asInt, name)
            assertTrue(json("challenges", name)["badge"] == null, "$name must not award a badge again")
            members.forEach { line ->
                val fields = fields(line)
                assertTrue(fields.containsKey("held_item"), "$name member without an item: $line")
                assertEquals(4, fields.getValue("moves").split(',').size, "$name member without four moves: $line")
                assertTrue(stats.count { fields.getValue("${it}_iv") == "31" } >= 5, "$name member below 5V: $line")
                assertTrue(stats.sumOf { fields.getValue("${it}_ev").toInt() } >= 508, "$name member with unspent EVs: $line")
            }
        }
        assertEquals(85, gyms.maxOf { team("${it}_hard").maxOf(::level) })
        assertEquals(100, finals.dropLast(1).maxOf { team("${it}_hard").maxOf(::level) })
        assertEquals(110, level(team("cynthia_hard").last()))
    }

    @Test
    fun `level caps follow the route through both difficulties`() {
        fun ace(name: String) = team(name).maxOf(::level)
        fun unlock(name: String) = json("rewards", name)["unlock_cap"].asInt
        // Normal: each cap is the next opponent's highest level plus two.
        assertEquals(ace(gyms.first()) + 2, json("leagues", "active")["initial_cap"].asInt)
        gyms.zipWithNext().forEach { (cleared, next) -> assertEquals(ace(next) + 2, unlock(cleared), cleared) }
        // The Elite Four and the Champion are one run with one party, so its cap covers the strongest of them.
        val finalsCap = finals.maxOf(::ace) + 2
        assertEquals(finalsCap, unlock(gyms.last()))
        finals.dropLast(1).forEach { assertEquals(finalsCap, unlock(it), it) }
        // The normal Champion opens hard at 80; the hard gyms keep it until the last one lifts it to 100.
        assertEquals(80, unlock("cynthia"))
        gyms.dropLast(1).forEach { assertEquals(80, unlock("${it}_hard"), it) }
        (listOf(gyms.last()) + finals).forEach { assertEquals(100, unlock("${it}_hard"), it) }
        val route = json("leagues", "active").getAsJsonObject("hard")
        assertEquals(gyms.map { "$ns:${it}_hard" }, route.getAsJsonArray("gyms").map { it.asString })
        assertEquals(finals.map { "$ns:${it}_hard" }, route.getAsJsonArray("finals").map { it.asString })
    }

    private class GameData(
        val species: Set<String>,
        val moves: Set<String>,
        val abilities: Set<String>,
        val items: Set<String>,
        /** Mega Stone item ID to the species it Mega Evolves. */
        val megaStones: Map<String, String>,
    )

    companion object {
        private lateinit var data: GameData

        /** Mega Showdown is not on the test classpath; the unitTest task passes where its jar is. */
        private fun megaShowdownJar(): JarFile = JarFile(File(requireNotNull(System.getProperty("league.megaShowdownJar")) {
            "Run through the unitTest task, which passes league.megaShowdownJar"
        }))

        @JvmStatic
        @BeforeAll
        fun loadGameData() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            // The property parser reads Cobblemon's config, which only the mod's own start-up normally creates.
            Cobblemon.config = CobblemonConfig().also { it.maxPokemonLevel = 110 }
            val path = Paths.get(PokemonSpecies::class.java.protectionDomain.codeSource.location.toURI())
            val cobblemon = JarFile(path.toFile())
            val mega = megaShowdownJar()
            cobblemon.use { jar ->
                mega.use { megaJar ->
                    val names = jar.entries().asSequence().map { it.name }.toList()
                    val megaNames = megaJar.entries().asSequence().map { it.name }.toList()
                    fun keys(text: String) = Regex("^  ([a-z0-9]+): \\{", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
                    val showdown = HashMap<String, String>()
                    ZipInputStream(jar.getInputStream(jar.getEntry("data/cobblemon/showdown.zip"))).use { zip ->
                        generateSequence { zip.nextEntry }.forEach { entry ->
                            if (entry.name == "data/moves.js" || entry.name == "data/abilities.js") showdown[entry.name] = zip.readBytes().decodeToString()
                        }
                    }
                    val speciesEntries = names.filter { it.startsWith("data/cobblemon/species/") && it.endsWith(".json") }
                        .associateBy { it.substringAfterLast('/').removeSuffix(".json") }
                    val megaStones = megaNames.filter { it.startsWith("data/mega_showdown/mega_showdown/mega/") && it.endsWith(".json") }
                        .associate { entry ->
                            val root = megaJar.getInputStream(megaJar.getEntry(entry)).reader().use(JsonParser::parseReader).asJsonObject
                            "mega_showdown:${entry.substringAfterLast('/').removeSuffix(".json")}" to
                                root.getAsJsonArray("pokemons").first().asString.lowercase()
                        }
                    data = GameData(
                        species = speciesEntries.keys,
                        moves = keys(showdown.getValue("data/moves.js")),
                        abilities = keys(showdown.getValue("data/abilities.js")),
                        items = names.filter { it.startsWith("assets/cobblemon/models/item/") }.map { "cobblemon:" + it.substringAfterLast('/').removeSuffix(".json") }.toSet() +
                            megaNames.filter { it.startsWith("assets/mega_showdown/models/item/") }.map { "mega_showdown:" + it.substringAfterLast('/').removeSuffix(".json") },
                        megaStones = megaStones,
                    )
                    registerUsedSpeciesAndAbilities(jar, speciesEntries)
                }
            }
        }

        /** The parser resolves species and abilities by name, so register the ones the teams use, as bare entries. */
        private fun registerUsedSpeciesAndAbilities(jar: JarFile, speciesEntries: Map<String, String>) {
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            val names = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner", "aaron", "bertha",
                "flint", "lucian", "cynthia").flatMap { listOf(it, "${it}_hard") }
            val lines = names.flatMap { name ->
                JsonParser.parseString(LeagueTeamDataTest::class.java
                    .getResourceAsStream("/data/more_cobblemon_contents_league_challenge/league-challenge/teams/$name.json")!!
                    .bufferedReader().use { it.readText() }).asJsonObject.getAsJsonArray("pokemon").map { it.asString }
            }
            lines.map { line -> line.split(' ').first { it.startsWith("ability=") }.substringAfter('=') }.toSet()
                .filter { Abilities.get(it) == null }
                .forEach { Abilities.register(AbilityTemplate(name = it, displayName = it, description = it)) }
            PokemonSpecies.reload(lines.map { it.substringBefore(' ') }.toSet().filter { it in speciesEntries }.associate { species ->
                val id = ResourceLocation.fromNamespaceAndPath("cobblemon", species)
                val root = jar.getInputStream(jar.getEntry(speciesEntries.getValue(species))).reader().use(JsonParser::parseReader).asJsonObject
                id to Species().also {
                    it.name = root["name"].asString
                    it.resourceIdentifier = id
                    it.implemented = true
                    it.initialize()
                }
            })
        }
    }
}
