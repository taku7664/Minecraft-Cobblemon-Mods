package jbro.cobblemon.dimensions.worldgen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.JsonOps
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.OverworldBiomeBuilder

/**
 * The overworld the dimensions are built from, and this mod's own templates. [read] gives a data file's text by its
 * path inside a jar (`data/minecraft/...`), or null when it is missing.
 */
class WorldgenSource private constructor(
    /** "Terralith" or "vanilla", for the log. */
    val name: String,
    private val terralith: Boolean,
    private val readTerralith: (String) -> String?,
    private val readVanilla: (String) -> String?,
    private val readOwn: (String) -> String?,
) {
    /** The overworld's biome layout: entries of `biome` and climate `parameters`. */
    fun layout(): JsonArray =
        if (terralith) {
            // Terralith keeps its whole layout here; with Lithostitched installed the game does not load the file, but
            // the table in it is still Terralith's layout.
            json(readTerralith, "data/minecraft/dimension/overworld.json").asJsonObject
                .getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes")
        } else {
            vanillaLayout()
        }

    /** The overworld's terrain settings. */
    fun noiseSettings(): JsonObject = json(read(), "data/minecraft/worldgen/noise_settings/overworld.json").asJsonObject

    /** The biome whose carvers and features one of this mod's biomes borrows, by its `features` entry. */
    fun biome(features: JsonObject): JsonObject {
        val id = features.get(if (terralith) "terralith" else "vanilla").asString
        val (namespace, path) = id.split(':', limit = 2)
        return json(read(), "data/$namespace/worldgen/biome/$path.json").asJsonObject
    }

    /** Ultra Space's own surface: this mod's, not the source's. */
    fun ultraSurfaceRule(): JsonElement = json(readOwn, "cobblemon_dimensions/ultra_space_surface.json")

    /** Terralith reads its own files first, then vanilla ones it does not replace. */
    private fun read(): (String) -> String? = if (terralith) { path -> readTerralith(path) ?: readVanilla(path) } else readVanilla

    private fun json(read: (String) -> String?, path: String): JsonElement =
        JsonParser.parseString(read(path) ?: error("$name worldgen: $path is missing"))

    companion object {
        fun terralith(readTerralith: (String) -> String?, readVanilla: (String) -> String?, readOwn: (String) -> String?) =
            WorldgenSource("Terralith", true, readTerralith, readVanilla, readOwn)

        fun vanilla(readVanilla: (String) -> String?, readOwn: (String) -> String?) =
            WorldgenSource("vanilla", false, { null }, readVanilla, readOwn)

        /** Vanilla's overworld layout lives in code, not in a data file; this writes it out the way a data file would. */
        fun vanillaLayout(): JsonArray {
            val out = JsonArray()
            OverworldBiomeBuilder().addBiomes { pair: Pair<Climate.ParameterPoint, ResourceKey<Biome>> ->
                val parameters = Climate.ParameterPoint.CODEC.encodeStart(JsonOps.INSTANCE, pair.first).getOrThrow()
                out.add(JsonObject().apply {
                    addProperty("biome", pair.second.location().toString())
                    add("parameters", parameters)
                })
            }
            return out
        }
    }
}
