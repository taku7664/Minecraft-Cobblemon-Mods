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
    /** Seconds between two `[안내]` tips; 0 turns them off. */
    val tipIntervalSeconds: Int = 30,
    /** The tips, one picked at random each time. */
    val tips: List<String> = DEFAULT_TIPS,
) {
    init {
        require(tipIntervalSeconds >= 0) { "Tip interval cannot be negative" }
        require(wildHiddenAbilityRate in 0..100) { "Hidden ability rate must be between 0 and 100" }
        require(!wildIvEnabled || wildIvRanges.isNotEmpty()) { "At least one wild IV range is required" }
    }

    companion object {
        val DEFAULT_IV_RANGES = listOf(IvRange(0, 9, 20.0), IvRange(10, 19, 45.0), IvRange(20, 29, 25.0), IvRange(30, 31, 10.0))
        val DEFAULT_TIPS = listOf(
            "/plaza enter로 광장에 갈 수 있습니다. 돌아올 때는 /plaza exit를 입력하세요.",
            "/pokenav로 아이템 없이 포켓내비를 열 수 있습니다.",
            "/legends <포켓몬>으로 그 전설을 직접 잡았는지 확인할 수 있습니다.",
            "전설 포켓몬은 불러낸 트레이너만 배틀하고 잡을 수 있습니다.",
            "리그 등급이 오를수록 잡을 수 있는 전설이 늘어납니다.",
            "전설은 종마다 한 마리씩만 직접 잡을 수 있습니다.",
            "/release <슬롯>으로 파티 포켓몬을 놓아줄 수 있습니다.",
            "포켓몬 아이템을 들고 우클릭하면 포켓몬으로 되돌릴 수 있습니다.",
            "한 번도 쓰지 않은 포케스낵은 부수면 그대로 돌려받습니다.",
        )
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
                root.get("tipIntervalSeconds")?.asInt ?: defaults.tipIntervalSeconds,
                root.getAsJsonArray("tips")?.map { it.asString } ?: defaults.tips,
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
            addProperty("tipIntervalSeconds", config.tipIntervalSeconds)
            add("tips", gson.toJsonTree(config.tips))
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
