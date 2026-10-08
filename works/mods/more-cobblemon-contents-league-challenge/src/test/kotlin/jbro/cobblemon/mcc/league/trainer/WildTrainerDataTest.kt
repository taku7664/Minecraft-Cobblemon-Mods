package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildTrainerDataTest {
    private val ns = "more_cobblemon_contents_league_challenge"
    private val resources = Path.of("src/main/resources")
    private val definitions = WildTrainerCatalogParser.parse(
        Files.list(resources.resolve("data/$ns/league-challenge/wild_trainers")).use { files ->
            files.toList().filter { it.name.endsWith(".json") }.associate { it.name to it.readText() }
        },
    )

    @Test
    fun `every kind has its NPC class and every skin it wears is in the variation`() {
        val variation = JsonParser.parseString(resources.resolve("assets/$ns/bedrock/npcs/variations/wild_trainer/0_wild_trainer.json").readText())
            .asJsonObject.getAsJsonArray("variations").flatMap { entry -> entry.asJsonObject.getAsJsonArray("aspects").map { it.asString } }.toSet()
        assertTrue(definitions.values.count { it.tier == WildTrainerTier.NORMAL } >= 100)
        assertTrue(definitions.values.count { it.tier == WildTrainerTier.ACE } >= 20)
        definitions.keys.forEach { npcClass ->
            val file = resources.resolve("data/$ns/npcs/${npcClass.substringAfter(':')}.json")
            assertTrue(Files.exists(file), npcClass)
            val npc = JsonParser.parseString(file.readText()).asJsonObject
            assertEquals("$ns:wild_trainer", npc.get("resourceIdentifier").asString, npcClass)
            val skins = npc.getAsJsonObject("variation").getAsJsonArray("skin").map { it.asString }
            assertTrue(skins.isNotEmpty(), npcClass)
            assertTrue(variation.containsAll(skins), npcClass)
        }
    }

    @Test
    fun `no gym leader, Elite Four, Champion, named character or villain skin is worn`() {
        val blocked = Regex("^rct_(leader|gym_leader|sinnoh_leader|elite_four|champion|rival|title_defense|battleground|boss|commander|" +
            "rocket_admin|shadow_admin|light_of_ruin|professor|prof|player|pokemon_trainer|team_rocket|team_galactic|shadow_grunt|burglar|" +
            "expert|idol|double_team)_")
        val worn = Files.list(resources.resolve("data/$ns/npcs")).use { files ->
            files.toList().filter { it.name.endsWith(".json") }.flatMap { file ->
                JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("variation").getAsJsonArray("skin").map { it.asString }
            }
        }
        assertTrue(worn.size > 1000)
        assertTrue(worn.none(blocked::containsMatchIn), worn.filter(blocked::containsMatchIn).take(5).toString())
    }

    @Test
    fun `aces pay twice what normal trainers pay`() {
        // A light side income: the Tower and the Factory pay better for the harder battles.
        definitions.values.filter { it.role == WildNpcRole.BATTLE }.forEach { definition ->
            assertEquals(if (definition.tier == WildTrainerTier.ACE) 6L else 3L, definition.bp, definition.npcClass)
        }
    }

    @Test
    fun `every species in the pools is one Cobblemon has implemented and none is legendary`() {
        val cobblemon = JarFile(Paths.get(PokemonSpecies::class.java.protectionDomain.codeSource.location.toURI()).toFile())
        val usable = cobblemon.use { jar ->
            jar.entries().asSequence().filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                .mapNotNull { entry ->
                    val species = JsonParser.parseString(jar.getInputStream(entry).reader().readText()).asJsonObject
                    val labels = species.getAsJsonArray("labels")?.map { it.asString }.orEmpty()
                    val implemented = species.get("implemented")?.asBoolean == true
                    val special = labels.any { it in setOf("legendary", "mythical", "ultra_beast", "paradox", "restricted") }
                    entry.name.substringAfterLast('/').removeSuffix(".json").takeIf { implemented && !special }
                }.toSet()
        }
        val unusable = definitions.values.flatMap { definition -> definition.pokemon.map { it.species } }.distinct().filter { it !in usable }
        assertTrue(unusable.isEmpty(), unusable.toString())
    }

    @Test
    fun `parties follow the challenger's cap and never pass it`() {
        val random = Random(7)
        definitions.values.filter { it.role == WildNpcRole.BATTLE }.forEach { definition ->
            listOf(16, 24, 34, 46, 64, 80, 100).forEach { cap ->
                repeat(20) {
                    val party = WildTrainerParty.roll(definition, cap, random)
                    assertTrue(party.size in 1..6, "${definition.npcClass} $cap ${party.size}")
                    assertTrue(party.all { (_, level) -> level in 1..cap }, "${definition.npcClass} $cap $party")
                }
            }
        }
    }

    @Test
    fun `trainers are named after the person their skin belongs to`() {
        assertEquals("Elizabeth", WildTrainers.personalName("rct_aroma_lady_elizabeth_02f7"))
        assertEquals("Kati", WildTrainers.personalName("rct_waitress_kati_03fc"))
        assertNull(WildTrainers.personalName("rct_"))
        assertFalse(WildTrainers.personalName("rct_hiker_bob_0123").isNullOrEmpty())
    }

    @Test
    fun `wild trainers appear only in the overworld, never in the plaza, a MyRoom or the lounge`() {
        assertTrue(WildTrainers.isWild("minecraft:overworld"))
        listOf("jbro_policy:plaza", "myroom:rooms", "more_cobblemon_contents:battle_lounge", "minecraft:the_nether", "minecraft:the_end",
            "some_mod:new_dimension").forEach { assertFalse(WildTrainers.isWild(it), it) }
    }
}
