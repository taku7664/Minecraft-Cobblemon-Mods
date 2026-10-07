package jbro.cobblemon.dimensions.worldgen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Builds the dimensions' biome layouts, terrain settings and biomes from `cobblemon_dimensions/worldgen.json` and an
 * overworld to copy from ([WorldgenSource]): Terralith's when it is installed, vanilla Minecraft's when not. Nothing
 * taken from Terralith ships in this mod; it is read from the installed Terralith when the game starts.
 *
 * Every dimension keeps the source's overworld terrain. It takes the source's whole overworld biome layout (every
 * climate entry) and only renames each entry's biome to one of its own, so a desert canyon stays a canyon and becomes,
 * say, Ultra Desert. Each biome borrows the features of one source biome. Ultra Space floats that terrain as islands.
 *
 * The result maps paths under `data/cobblemon_dimensions/` to their JSON.
 */
object DimensionWorldgen {
    private const val NS = "cobblemon_dimensions"
    private const val ULTRA_SPACE = "ultra_space"

    fun generate(spec: JsonObject, source: WorldgenSource): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        val underground = spec.getAsJsonArray("underground").map { it.asString }
        val layout = source.layout()
        val overworld = source.noiseSettings()
        for ((dimension, value) in spec.getAsJsonObject("dimensions").entrySet()) {
            val dim = value.asJsonObject
            val biomes = dim.getAsJsonObject("biomes")

            val entries = JsonArray()
            for (entry in layout) {
                val biome = entry.asJsonObject.get("biome").asString
                // Underground biomes would scatter through the ground; it takes the surface biome above.
                if (underground.any { it in biome }) continue
                entries.add(obj("biome" to "$NS:${biomeFor(dim, biome)}", "parameters" to entry.asJsonObject.get("parameters")))
            }
            out["dimension/$dimension.json"] = obj(
                "type" to "$NS:$dimension",
                "generator" to obj(
                    "type" to "minecraft:noise",
                    "settings" to "$NS:$dimension",
                    "biome_source" to obj("type" to "minecraft:multi_noise", "biomes" to entries),
                ),
            )

            val settings = overworld.deepCopy()
            if (dimension == ULTRA_SPACE) {
                floatIslands(settings, source.ultraSurfaceRule())
                out["worldgen/density_function/ultra_space/island_mask.json"] = islandMask(overworld.getAsJsonObject("noise_router").get("depth"))
            } else {
                settings.add("surface_rule", surfaceRule(biomes))
            }
            out["worldgen/noise_settings/$dimension.json"] = settings

            for ((name, biome) in biomes.entrySet()) {
                out["worldgen/biome/$name.json"] = biome(biome.asJsonObject, source)
            }
        }
        return out
    }

    /**
     * The first rule that names [biome] exactly (`names`) or whose fragment appears in its name (`fragments`) decides;
     * otherwise the dimension's default. Exact names come first in the spec, to even out how much ground each biome gets.
     */
    fun biomeFor(dimension: JsonObject, biome: String): String {
        val name = biome.substringAfter(':')
        for (rule in dimension.getAsJsonArray("rules")) {
            val r = rule.asJsonObject
            val named = r.getAsJsonArray("names")?.any { it.asString == name } == true
            if (named || r.getAsJsonArray("fragments")?.any { it.asString in name } == true) return r.get("biome").asString
        }
        return dimension.get("default").asString
    }

    private fun biome(spec: JsonObject, source: WorldgenSource): JsonObject {
        val colors = spec.getAsJsonObject("colors")
        val effects = obj(
            "sky_color" to colors.get("sky"), "fog_color" to colors.get("fog"), "water_color" to colors.get("water"),
            "water_fog_color" to colors.get("fog"), "grass_color" to colors.get("grass"), "foliage_color" to colors.get("foliage"),
        )
        spec.getAsJsonObject("particle")?.let { particle ->
            effects.add("particle", obj("options" to obj("type" to particle.get("type")), "probability" to particle.get("probability")))
        }
        val borrowed = source.biome(spec.getAsJsonObject("features"))
        return obj(
            "has_precipitation" to false, "temperature" to 0.8, "downfall" to 0.4,
            "effects" to effects,
            // Vanilla mobs stay out; Pokemon come from Cobblemon's spawn pools.
            "spawners" to JsonObject(), "spawn_costs" to JsonObject(),
            "carvers" to (borrowed.get("carvers") ?: JsonObject()), "features" to borrowed.get("features"),
        )
    }

    /**
     * Ultra Space: the overworld's terrain cut into floating islands, with small sky islands above, no sea and no
     * aquifers, so no water hangs in the air. The island shapes live in `worldgen/density_function/ultra_space/`.
     */
    private fun floatIslands(settings: JsonObject, surface: JsonElement) {
        settings.addProperty("sea_level", -64)
        settings.addProperty("aquifers_enabled", false)
        // Coarser vertical cells make the island undersides hang in longer drips. No spawn search: entering finds land.
        settings.getAsJsonObject("noise").addProperty("size_vertical", 2)
        settings.add("spawn_target", JsonArray())
        val router = settings.getAsJsonObject("noise_router")
        router.add("final_density", obj(
            "type" to "minecraft:max",
            "argument1" to obj(
                "type" to "minecraft:min",
                "argument1" to obj("type" to "minecraft:add", "argument1" to router.get("final_density"),
                    "argument2" to interpolated("$NS:ultra_space/warp")),
                "argument2" to interpolated("$NS:ultra_space/island_mask"),
            ),
            "argument2" to interpolated("$NS:ultra_space/sky_islands"),
        ))
        settings.add("surface_rule", surface)
    }

    /** Island footprints from 2D noise, kept as deep below the surface as the noise allows, with ragged undersides. */
    private fun islandMask(depth: JsonElement): JsonObject = obj(
        "type" to "minecraft:add",
        "argument1" to obj("type" to "minecraft:add", "argument1" to -0.3, "argument2" to obj(
            "type" to "minecraft:mul", "argument1" to 2.0,
            "argument2" to obj("type" to "minecraft:noise", "noise" to "$NS:ultra_islands", "xz_scale" to 1.0, "y_scale" to 0.0),
        )),
        "argument2" to obj(
            "type" to "minecraft:add",
            "argument1" to obj("type" to "minecraft:mul", "argument1" to -1.7, "argument2" to depth),
            "argument2" to "$NS:ultra_space/rough",
        ),
    )

    private fun interpolated(function: String) = obj("type" to "minecraft:interpolated", "argument" to function)

    /** Bedrock floor and deepslate depths like the overworld, then each biome's top and under blocks. */
    private fun surfaceRule(biomes: JsonObject): JsonObject {
        val rules = JsonArray()
        rules.add(obj(
            "type" to "minecraft:condition",
            "if_true" to obj("type" to "minecraft:vertical_gradient", "random_name" to "minecraft:bedrock_floor",
                "true_at_and_below" to obj("above_bottom" to 0), "false_at_and_above" to obj("above_bottom" to 5)),
            "then_run" to block("minecraft:bedrock"),
        ))
        for ((name, value) in biomes.entrySet()) {
            val surface = value.asJsonObject.getAsJsonObject("surface") ?: continue
            var top: JsonObject = block(surface.get("top").asString)
            val under = block(surface.get("under").asString)
            if (surface.get("grass")?.asBoolean == true) {
                // Grass only where no water stands on it.
                top = sequence(obj("type" to "minecraft:condition",
                    "if_true" to obj("type" to "minecraft:water", "offset" to -1, "surface_depth_multiplier" to 0, "add_stone_depth" to false),
                    "then_run" to top), under)
            }
            surface.get("patch")?.takeUnless { it.isJsonNull }?.let { patch ->
                top = sequence(obj("type" to "minecraft:condition",
                    "if_true" to obj("type" to "minecraft:noise_threshold", "noise" to "minecraft:surface",
                        "min_threshold" to 0.35, "max_threshold" to 1.0),
                    "then_run" to block(patch.asString)), top)
            }
            fun floor(add: Boolean) = obj("type" to "minecraft:stone_depth", "offset" to 0, "surface_type" to "floor",
                "add_surface_depth" to add, "secondary_depth_range" to 0)
            rules.add(obj(
                "type" to "minecraft:condition",
                "if_true" to obj("type" to "minecraft:biome", "biome_is" to JsonArray().apply { add("$NS:$name") }),
                "then_run" to sequence(
                    obj("type" to "minecraft:condition", "if_true" to floor(false), "then_run" to top),
                    obj("type" to "minecraft:condition", "if_true" to floor(true), "then_run" to under),
                ),
            ))
        }
        rules.add(obj(
            "type" to "minecraft:condition",
            "if_true" to obj("type" to "minecraft:vertical_gradient", "random_name" to "minecraft:deepslate",
                "true_at_and_below" to obj("absolute" to 0), "false_at_and_above" to obj("absolute" to 8)),
            "then_run" to obj("type" to "minecraft:block",
                "result_state" to obj("Name" to "minecraft:deepslate", "Properties" to obj("axis" to "y"))),
        ))
        return obj("type" to "minecraft:sequence", "sequence" to rules)
    }

    private fun block(name: String) = obj("type" to "minecraft:block", "result_state" to obj("Name" to name))

    private fun sequence(vararg rules: JsonObject) = obj("type" to "minecraft:sequence", "sequence" to JsonArray().apply { rules.forEach(::add) })

    private fun obj(vararg entries: Pair<String, Any?>): JsonObject = JsonObject().apply {
        for ((key, value) in entries) add(key, when (value) {
            null -> null
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> error("Cannot put a ${value::class.simpleName} in JSON")
        })
    }
}
