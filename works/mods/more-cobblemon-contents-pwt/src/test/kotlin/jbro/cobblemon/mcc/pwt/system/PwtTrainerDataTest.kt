package jbro.cobblemon.mcc.pwt.system

import com.google.gson.JsonParser
import java.io.File
import java.net.JarURLConnection
import jbro.cobblemon.mcc.internal.battle.LegendaryClassPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bundled roster against Cobblemon's own data: every species, move, ability and item exists and fits. */
class PwtTrainerDataTest {
    private val resources = File("src/main/resources")
    private val trainerDir = File(resources, "data/more_cobblemon_contents_pwt/pwt/trainers")
    private val files = trainerDir.listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
        .associate { "more_cobblemon_contents_pwt:${it.nameWithoutExtension}" to it.readText() }
    private val catalog = PwtCatalogParser.parse(files)

    // Found by a species file only Cobblemon has: other mods also ship an assets/cobblemon/lang/en_us.json.
    private val cobblemonJar = (javaClass.classLoader.getResource("data/cobblemon/species/generation1/bulbasaur.json")!!.openConnection() as JarURLConnection).jarFile
    private val lang = JsonParser.parseString(cobblemonJar.getInputStream(cobblemonJar.getEntry("assets/cobblemon/lang/en_us.json"))
        .reader().readText()).asJsonObject
    private val species = cobblemonJar.entries().asSequence()
        .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
        .associate { it.name.substringAfterLast('/').removeSuffix(".json") to JsonParser.parseString(cobblemonJar.getInputStream(it).reader().readText()).asJsonObject }

    @Test
    fun `the roster parses and fills a bracket`() {
        assertTrue(catalog.trainers.size >= PwtBracket.SIZE - 1)
        catalog.trainers.values.forEach { trainer ->
            assertTrue(lang.has("cobblemon.species.${trainer.ace}.name"), "${trainer.id} ace ${trainer.ace}")
        }
    }

    @Test
    fun `no legendary class Pokemon on either side`() {
        val legendary = catalog.trainers.values.flatMap { trainer ->
            trainer.pool.map { it.substringBefore(' ') }.filter { LegendaryClassPolicy.categoryFor("cobblemon:$it") != null }.map { "${trainer.id} $it" }
        }
        assertEquals(emptyList<String>(), legendary)
    }

    @Test
    fun `every species move ability and item is Cobblemon's and learnable`() {
        val problems = catalog.trainers.values.flatMap { trainer ->
            trainer.pool.flatMap { line ->
                val parts = line.split(' ')
                val name = parts.first()
                val properties = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
                val data = species[name] ?: return@flatMap listOf("${trainer.id}: unknown species $name")
                val learnable = data.getAsJsonArray("moves").map { it.asString.substringAfter(':') }.toSet()
                val abilities = data.getAsJsonArray("abilities").map { it.asString.removePrefix("h:") }.toSet()
                buildList {
                    properties.getValue("moves").split(',').forEach { move ->
                        if (!lang.has("cobblemon.move.$move")) add("${trainer.id}: $name unknown move $move")
                        else if (move !in learnable) add("${trainer.id}: $name cannot learn $move")
                    }
                    val ability = properties.getValue("ability")
                    if (ability !in abilities) add("${trainer.id}: $name cannot have $ability")
                    properties["held_item"]?.let { item ->
                        if (!lang.has("item.${item.replace(':', '.')}")) add("${trainer.id}: $name unknown item $item")
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `skins this mod ships exist and every name has a translation`() {
        val ko = JsonParser.parseString(File(resources, "assets/more_cobblemon_contents_pwt/lang/ko_kr.json").readText()).asJsonObject
        val en = JsonParser.parseString(File(resources, "assets/more_cobblemon_contents_pwt/lang/en_us.json").readText()).asJsonObject
        catalog.trainers.values.forEach { trainer ->
            if (trainer.skin.startsWith("more_cobblemon_contents_pwt:")) {
                assertTrue(File(resources, "assets/more_cobblemon_contents_pwt/" + trainer.skin.substringAfter(':')).isFile, trainer.skin)
            }
            assertTrue(ko.has(trainer.nameKey) && en.has(trainer.nameKey), trainer.nameKey)
        }
        assertEquals(ko.keySet(), en.keySet())
    }
}
