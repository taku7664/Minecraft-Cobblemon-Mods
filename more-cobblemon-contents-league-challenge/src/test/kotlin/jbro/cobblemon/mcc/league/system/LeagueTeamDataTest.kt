package jbro.cobblemon.mcc.league.system

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.JsonParser
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
 * The bundled Sinnoh League reproduces the Platinum first battles (pret/pokeplatinum trainer data): species, level,
 * four moves, held item, the fixed IVs and the nature, ability and gender the game derives from each trainer, with
 * no EVs. Every value must be one Cobblemon knows, since its property parser silently drops what it does not.
 */
class LeagueTeamDataTest {
    private val ns = "more_cobblemon_contents_league_challenge"
    private val gyms = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner")
    private val finals = listOf("aaron", "bertha", "flint", "lucian", "cynthia")
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

    @Test
    fun `every team member names only species, moves, abilities, natures and items Cobblemon has`() {
        (gyms + finals).forEach { name ->
            team(name).forEach { line ->
                val species = line.substringBefore(' ')
                val fields = fields(line)
                val at = "$name: $line"
                assertTrue(species in cobblemon.species, "unknown species in $at")
                val moves = fields.getValue("moves").split(',')
                assertTrue(moves.size in 1..4 && moves.all { it in cobblemon.moves }, "unknown move in $at")
                assertTrue(fields.getValue("ability") in cobblemon.abilities, "unknown ability in $at")
                assertTrue(fields.getValue("nature") in natures, "unknown nature in $at")
                fields["gender"]?.let { assertTrue(it == "male" || it == "female", "bad gender in $at") }
                fields["held_item"]?.let { assertTrue(it.removePrefix("cobblemon:") in cobblemon.items, "unknown item in $at") }
                stats.forEach { stat ->
                    assertTrue(fields.getValue("${stat}_iv").toInt() in 0..31, "bad $stat IV in $at")
                    assertEquals("0", fields.getValue("${stat}_ev"), "Platinum trainers have no EVs: $at")
                }
            }
        }
    }

    @Test
    fun `Cobblemon's parser keeps every value of every team member`() {
        (gyms + finals).forEach { name ->
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
                    assertEquals(0, evs[stat], "$key EV $at")
                }
            }
        }
    }

    @Test
    fun `spot checks against the Platinum data`() {
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
    fun `each level cap is the next opponent's highest level plus two`() {
        fun ace(name: String) = team(name).maxOf { fields(it).getValue("level").toInt() }
        fun unlock(name: String) = json("rewards", name)["unlock_cap"].asInt
        assertEquals(ace(gyms.first()) + 2, json("leagues", "active")["initial_cap"].asInt)
        gyms.zipWithNext().forEach { (cleared, next) -> assertEquals(ace(next) + 2, unlock(cleared), cleared) }
        // The Elite Four and the Champion are one run with one party, so its cap covers the strongest of them.
        val finalsCap = finals.maxOf(::ace) + 2
        assertEquals(finalsCap, unlock(gyms.last()))
        finals.dropLast(1).forEach { assertEquals(finalsCap, unlock(it), it) }
        assertEquals(100, unlock(finals.last()))
    }

    private class CobblemonData(val species: Set<String>, val moves: Set<String>, val abilities: Set<String>, val items: Set<String>)

    companion object {
        private lateinit var cobblemon: CobblemonData

        @JvmStatic
        @BeforeAll
        fun loadCobblemonData() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            // The property parser reads Cobblemon's config, which only the mod's own start-up normally creates.
            Cobblemon.config = CobblemonConfig()
            val path = Paths.get(PokemonSpecies::class.java.protectionDomain.codeSource.location.toURI())
            JarFile(path.toFile()).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.toList()
                fun keys(text: String) = Regex("^  ([a-z0-9]+): \\{", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
                val showdown = HashMap<String, String>()
                ZipInputStream(jar.getInputStream(jar.getEntry("data/cobblemon/showdown.zip"))).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (entry.name == "data/moves.js" || entry.name == "data/abilities.js") showdown[entry.name] = zip.readBytes().decodeToString()
                    }
                }
                val speciesEntries = names.filter { it.startsWith("data/cobblemon/species/") && it.endsWith(".json") }
                    .associateBy { it.substringAfterLast('/').removeSuffix(".json") }
                // The parser resolves species by name, so register the species the teams use, as bare entries.
                ExperienceGroups.registerDefaults()
                if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
                val lines = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner", "aaron", "bertha",
                    "flint", "lucian", "cynthia").flatMap { name ->
                    JsonParser.parseString(LeagueTeamDataTest::class.java
                        .getResourceAsStream("/data/more_cobblemon_contents_league_challenge/league-challenge/teams/$name.json")!!
                        .bufferedReader().use { it.readText() }).asJsonObject.getAsJsonArray("pokemon").map { it.asString }
                }
                val used = lines.map { it.substringBefore(' ') }.toSet()
                // Likewise the abilities, which a server loads from Showdown's data.
                lines.map { line -> line.split(' ').first { it.startsWith("ability=") }.substringAfter('=') }.toSet()
                    .filter { Abilities.get(it) == null }
                    .forEach { Abilities.register(AbilityTemplate(name = it, displayName = it, description = it)) }
                PokemonSpecies.reload(used.filter { it in speciesEntries }.associate { species ->
                    val id = ResourceLocation.fromNamespaceAndPath("cobblemon", species)
                    val root = jar.getInputStream(jar.getEntry(speciesEntries.getValue(species))).reader().use(JsonParser::parseReader).asJsonObject
                    id to Species().also {
                        it.name = root["name"].asString
                        it.resourceIdentifier = id
                        it.implemented = true
                        it.initialize()
                    }
                })
                cobblemon = CobblemonData(
                    species = speciesEntries.keys,
                    moves = keys(showdown.getValue("data/moves.js")),
                    abilities = keys(showdown.getValue("data/abilities.js")),
                    items = names.filter { it.startsWith("assets/cobblemon/models/item/") }
                        .map { it.substringAfterLast('/').removeSuffix(".json") }.toSet(),
                )
            }
        }
    }
}
