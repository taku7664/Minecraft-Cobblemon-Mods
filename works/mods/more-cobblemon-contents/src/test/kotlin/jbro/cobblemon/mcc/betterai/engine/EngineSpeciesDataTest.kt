package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.betterai.engine.dex.ShowdownSpeciesData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine builds the species Cobblemon registers at runtime the way Showdown's `new Species(data)` does.
 * The oracle runs Showdown's constructor on every Pokedex entry and on entries shaped like Cobblemon's data.
 */
class EngineSpeciesDataTest {
    private fun oracle(): List<Pair<JsonObject, JsonObject>> {
        EngineReferee.assumeAvailable()
        val showdown = System.getProperty("aiengine.showdown")
        val tools = Path.of(System.getProperty("aiengine.tools") ?: "tools/ai-engine")
        val out = Files.createTempFile("ai-engine-species", ".json")
        try {
            val process = ProcessBuilder("node", tools.resolve("species-oracle.cjs").toString(), "--showdown", showdown, "--out", out.toString())
                .redirectErrorStream(true).start()
            val console = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "Species oracle failed: $console" }
            return JsonParser.parseString(Files.readString(out)).asJsonArray.map { e ->
                e.asJsonObject.getAsJsonObject("raw") to e.asJsonObject.getAsJsonObject("species")
            }
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** JSON equality with numbers compared as numbers, as JavaScript sees them. */
    private fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> a == b
        a.isJsonPrimitive && b.isJsonPrimitive && a.asJsonPrimitive.isNumber && b.asJsonPrimitive.isNumber -> a.asDouble == b.asDouble
        a.isJsonObject && b.isJsonObject -> a.asJsonObject.keySet() == b.asJsonObject.keySet() &&
            a.asJsonObject.keySet().all { same(a.asJsonObject.get(it), b.asJsonObject.get(it)) }
        a.isJsonArray && b.isJsonArray -> a.asJsonArray.size() == b.asJsonArray.size() &&
            (0 until a.asJsonArray.size()).all { same(a.asJsonArray[it], b.asJsonArray[it]) }
        else -> a == b
    }

    @Test
    fun `species built from Cobblemon data match Showdown's constructor`() {
        val pairs = oracle()
        assertTrue(pairs.size > 1400) { "Oracle returned ${pairs.size} entries" }
        val differences = pairs.mapNotNull { (raw, expected) ->
            val actual = ShowdownSpeciesData.construct(raw)
            if (same(actual, expected)) return@mapNotNull null
            val keys = (actual.keySet() + expected.keySet()).filter { !same(actual.get(it), expected.get(it)) }
            "${expected.get("name")}: " + keys.joinToString { "$it engine=${actual.get(it)} showdown=${expected.get(it)}" }
        }
        assertTrue(differences.isEmpty()) { "${differences.size} species differ:\n" + differences.take(10).joinToString("\n") }
    }

    @Test
    fun `runtime species replace exported ones and number themselves like the registry`() {
        val dex = EngineReferee.dex
        val stock = requireNotNull(dex.species("clefable"))
        val custom = JsonObject().apply {
            addProperty("name", "Clefable")
            add("types", JsonParser.parseString("[\"Fairy\",\"Steel\"]"))
            add("baseStats", JsonParser.parseString("{\"hp\":95,\"atk\":70,\"def\":73,\"spa\":95,\"spd\":90,\"spe\":60}"))
            addProperty("num", 36)
        }
        val unnumbered = { name: String -> JsonObject().apply { addProperty("name", name); addProperty("num", 0) } }
        val runtime = dex.withSpecies(listOf(custom, unnumbered("Firstmon"), unnumbered("Secondmon")))
        assertEquals(listOf("Fairy"), stock.types)
        assertEquals(listOf("Fairy", "Steel"), runtime.species("clefable")!!.types)
        assertEquals(10002, runtime.species("firstmon")!!.num)
        assertEquals(10003, runtime.species("secondmon")!!.num)
        assertEquals(dex.species("garchomp")!!.baseStats, runtime.species("garchomp")!!.baseStats)
    }
}
