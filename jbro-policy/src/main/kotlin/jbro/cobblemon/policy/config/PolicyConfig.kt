package jbro.cobblemon.policy.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** Where `/plaza enter` lands. */
data class PlazaSpawn(val x: Double = 0.5, val y: Double = 80.0, val z: Double = 0.5, val yaw: Float = 0f, val pitch: Float = 0f) {
    init {
        require(listOf(x, y, z).all(Double::isFinite) && yaw.isFinite() && pitch.isFinite()) { "Plaza coordinates must be finite" }
    }
}

/** A wild IV band; [chance] is its relative weight, not a percentage that must add up. */
data class IvRange(val min: Int, val max: Int, val chance: Double) {
    init {
        require(min in 0..31 && max in min..31) { "IV range must satisfy 0 <= min <= max <= 31" }
        require(chance.isFinite() && chance > 0) { "IV range chance must be positive" }
    }
}

data class PolicyConfig(
    val plaza: PlazaSpawn = PlazaSpawn(),
    /** Percent chance that a wild, revived or command-made Pokemon gets its hidden ability. */
    val wildHiddenAbilityRate: Int = 30,
    val wildIvEnabled: Boolean = true,
    val wildIvRanges: List<IvRange> = DEFAULT_IV_RANGES,
) {
    init {
        require(wildHiddenAbilityRate in 0..100) { "Hidden ability rate must be between 0 and 100" }
        require(!wildIvEnabled || wildIvRanges.isNotEmpty()) { "At least one wild IV range is required" }
    }

    companion object {
        val DEFAULT_IV_RANGES = listOf(IvRange(0, 9, 20.0), IvRange(10, 19, 45.0), IvRange(20, 29, 25.0), IvRange(30, 31, 10.0))
        private val gson = GsonBuilder().setPrettyPrinting().create()

        /** Missing keys keep their defaults; a value that fails validation throws. */
        fun parse(json: String): PolicyConfig {
            val root = JsonParser.parseString(json).asJsonObject
            val defaults = PolicyConfig()
            val plaza = root.getAsJsonObject("plaza")?.let { p ->
                PlazaSpawn(p.double("x", defaults.plaza.x), p.double("y", defaults.plaza.y), p.double("z", defaults.plaza.z),
                    p.double("yaw", defaults.plaza.yaw.toDouble()).toFloat(), p.double("pitch", defaults.plaza.pitch.toDouble()).toFloat())
            } ?: defaults.plaza
            val iv = root.getAsJsonObject("wildIvDistribution")
            return PolicyConfig(
                plaza,
                root.get("wildHiddenAbilityRate")?.asInt ?: defaults.wildHiddenAbilityRate,
                iv?.get("enabled")?.asBoolean ?: defaults.wildIvEnabled,
                iv?.getAsJsonArray("ranges")?.map { element ->
                    val range = element.asJsonObject
                    IvRange(range.get("min").asInt, range.get("max").asInt, range.get("chance").asDouble)
                } ?: defaults.wildIvRanges,
            )
        }

        fun toJson(config: PolicyConfig): String = gson.toJson(JsonObject().apply {
            add("plaza", JsonObject().apply {
                addProperty("x", config.plaza.x); addProperty("y", config.plaza.y); addProperty("z", config.plaza.z)
                addProperty("yaw", config.plaza.yaw); addProperty("pitch", config.plaza.pitch)
            })
            addProperty("wildHiddenAbilityRate", config.wildHiddenAbilityRate)
            add("wildIvDistribution", JsonObject().apply {
                addProperty("enabled", config.wildIvEnabled)
                add("ranges", gson.toJsonTree(config.wildIvRanges))
            })
        })

        /** Writes the defaults when the file is missing; a broken file is left alone and the defaults run instead. */
        fun load(file: Path, warn: (String, Throwable?) -> Unit): PolicyConfig {
            if (!Files.exists(file)) {
                val defaults = PolicyConfig()
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, toJson(defaults))
                } catch (failure: java.io.IOException) { warn("Could not write the default config to $file", failure) }
                return defaults
            }
            return try { parse(Files.readString(file)) } catch (failure: RuntimeException) {
                warn("Invalid config at $file; it was left unchanged and the defaults run this time", failure)
                PolicyConfig()
            }
        }

        private fun JsonObject.double(key: String, fallback: Double) = get(key)?.asDouble ?: fallback
    }
}
