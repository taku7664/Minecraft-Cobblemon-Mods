package jbro.cobblemon.dimensions.worldgen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DimensionWorldgenTest {
    private fun classpath(path: String): String? = javaClass.classLoader.getResource(path)?.readText()

    private val spec: JsonObject = JsonParser.parseString(classpath("cobblemon_dimensions/worldgen.json")).asJsonObject

    private val terralithJar: ZipFile by lazy {
        val mods = File(System.getProperty("cobblemon_dimensions.server_mods") ?: error("server mods folder not given"))
        ZipFile(mods.listFiles().orEmpty().first { it.name.startsWith("Terralith_") })
    }

    private fun terralith(path: String): String? = terralithJar.getEntry(path)?.let { terralithJar.getInputStream(it).readBytes().decodeToString() }

    private val built by lazy { DimensionWorldgen.generate(spec, WorldgenSource.terralith(::terralith, ::classpath, ::classpath)) }
    private val vanilla by lazy { DimensionWorldgen.generate(spec, WorldgenSource.vanilla(::classpath, ::classpath)) }

    @Test
    fun `with Terralith it matches the files the mod used to ship`() {
        val shipped = built.keys.filter { classpath("data/cobblemon_dimensions/$it") != null }
        if (shipped.isEmpty()) return  // Already removed from the jar; nothing left to compare against.
        for (path in built.keys) {
            val old = JsonParser.parseString(classpath("data/cobblemon_dimensions/$path") ?: error("$path was not shipped"))
            assertEquals(old, built.getValue(path), path)
        }
    }

    @Test
    fun `both sources build every dimension, terrain setting and biome`() {
        for (files in listOf(built, vanilla)) {
            for (dimension in spec.getAsJsonObject("dimensions").keySet()) {
                assertTrue("dimension/$dimension.json" in files)
                assertTrue("worldgen/noise_settings/$dimension.json" in files)
            }
            assertTrue("worldgen/density_function/ultra_space/island_mask.json" in files)
            assertEquals(16, files.keys.count { it.startsWith("worldgen/biome/") })
        }
    }

    @Test
    fun `every biome of a dimension gets some of the layout`() {
        for (files in listOf(built, vanilla)) {
            for ((dimension, value) in spec.getAsJsonObject("dimensions").entrySet()) {
                val used = files.getValue("dimension/$dimension.json").asJsonObject.getAsJsonObject("generator")
                    .getAsJsonObject("biome_source").getAsJsonArray("biomes").map { it.asJsonObject.get("biome").asString.substringAfter(':') }.toSet()
                assertEquals(value.asJsonObject.getAsJsonObject("biomes").keySet(), used, dimension)
            }
        }
    }

    @Test
    fun `with Terralith no land biome takes much more of the layout than the others`() {
        for ((dimension, value) in spec.getAsJsonObject("dimensions").entrySet()) {
            val entries = built.getValue("dimension/$dimension.json").asJsonObject.getAsJsonObject("generator")
                .getAsJsonObject("biome_source").getAsJsonArray("biomes").map { it.asJsonObject.get("biome").asString.substringAfter(':') }
            val sea = value.asJsonObject.getAsJsonArray("rules")[0].asJsonObject.get("biome").asString
            val land = entries.filter { it != sea }.groupingBy { it }.eachCount()
            val share = land.values.max().toDouble() / land.values.sum()
            assertTrue(share < 1.5 / land.size) { "$dimension: $land" }
        }
    }

    @Test
    fun `without Terralith nothing points at Terralith`() {
        for ((path, json) in vanilla) {
            val text = json.toString()
            assertFalse("terralith:" in text, path)
            assertFalse("noise_router/" in text, path)  // Terralith's own density functions
        }
    }

    @Test
    fun `vanilla's layout leaves out its underground biomes`() {
        val layout = WorldgenSource.vanillaLayout()
        assertTrue(layout.size() > 1000)
        val biomes = vanilla.getValue("dimension/ancient.json").toString()
        assertFalse("minecraft:" in biomes.substringAfter("\"biomes\""))
    }

    @Suppress("unused")
    private fun pretty(json: JsonElement) = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json)
}
