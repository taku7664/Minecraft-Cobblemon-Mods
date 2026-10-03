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
    val tipIntervalSeconds: Int = 60,
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
            "/pokenav로 포켓몬 스폰 정보를 확인할 수 있습니다.",
            "/legends <포켓몬>으로 그 전설을 직접 잡았는지 확인할 수 있습니다.",
            "전설 포켓몬은 불러낸 트레이너만 배틀하고 잡을 수 있습니다.",
            "전설 포켓몬은 개인별로 이미 포획한 종은 스폰되지 않습니다.",
            "/release <슬롯>으로 파티 포켓몬을 놓아줄 수 있습니다.",
            "/poketoitem으로 포켓몬을 아이템으로 바꾸고, /itemtopoke로 다시 포켓몬으로 되돌릴 수 있습니다.",
            "/pokefusion으로 같은 진화 계보의 포켓몬을 합성하면 더 높은 개체값을 이어받습니다.",
            "레벨캡은 포켓몬을 키울 수 있는 최대 레벨입니다. 레벨캡에 닿은 포켓몬은 더 이상 레벨이 오르지 않고, 레벨캡보다 높은 포켓몬은 잡거나 배틀에 쓸 수 없습니다.",
            "레벨캡은 리그 챌린지에서 체육관을 깰 때마다 올라갑니다. 16에서 시작해 챔피언을 이기면 80까지 오릅니다.",
            "야생 포켓몬 레벨은 내 레벨캡을 기준으로 정해지고, 지역마다 레벨이 높은 포켓몬이 자주 나오는 곳과 레벨이 낮은 포켓몬이 자주 나오는 곳이 있습니다.",
            "야생 트레이너, 배틀 타워, 배틀 팩토리, 리그 챌린지에서 이기면 BP를 받습니다.",
            "모은 BP는 /mcc의 상점 탭에서 아이템을 사는 데 쓸 수 있습니다.",
            "/mcc의 PvP 탭에서 다른 트레이너와 대전할 수 있습니다.",
            "/room enter로 나만의 마이룸에 들어가고, /room exit로 원래 자리에 돌아옵니다.",
            "/room public true로 마이룸을 공개하면 다른 사람이 /room enter <닉네임>으로 놀러 올 수 있습니다.",
            "조작 설정에서 이모트 키를 지정하면, 그 키를 누른 채 방향을 골라 머리 위에 이모트를 띄울 수 있습니다.",
            "렉이 심하면 ESC → 모드 → Rounding-Block 설정에서 '둥근 블록 렌더링 사용'을 끄거나, /roundingblock enabled false를 입력해 보세요.",
            "셰이더를 켠 상태에서 렉이 심하면 ESC → 설정 → 비디오 설정 → 셰이더 팩에서 셰이더를 꺼 보세요.",
            "버그 제보, 건의, 신고는 /문의 <내용>으로 운영자에게 보낼 수 있습니다. 위키의 문의하기에서도 보낼 수 있습니다.",
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
