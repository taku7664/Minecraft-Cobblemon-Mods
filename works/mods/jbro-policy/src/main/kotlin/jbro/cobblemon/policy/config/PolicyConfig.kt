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
    /**
     * A wild, revived or command-made Pokemon gets its hidden ability 1 time in this many; 0 turns it off. The old
     * percent key `wildHiddenAbilityRate` is not read, so a config still holding it falls back to this default.
     */
    val wildHiddenAbilityOneIn: Int = 30,
    val wildIvEnabled: Boolean = true,
    val wildIvRanges: List<IvRange> = DEFAULT_IV_RANGES,
    /** Seconds between two `[안내]` tips; 0 turns them off. */
    val tipIntervalSeconds: Int = 60,
    /** The tips, one picked at random each time. */
    val tips: List<String> = DEFAULT_TIPS,
    /**
     * Other hub dimensions players hop between, such as player rooms. Entering the plaza from one keeps the return
     * point already saved, so `/plaza exit` still goes back to where the trip through the hubs began.
     */
    val plazaOtherHubDimensions: List<String> = DEFAULT_OTHER_HUBS,
) {
    init {
        require(tipIntervalSeconds >= 0) { "Tip interval cannot be negative" }
        require(wildHiddenAbilityOneIn >= 0) { "Hidden ability odds cannot be negative" }
        require(!wildIvEnabled || wildIvRanges.isNotEmpty()) { "At least one wild IV range is required" }
    }

    companion object {
        val DEFAULT_OTHER_HUBS = listOf("myroom:rooms")
        val DEFAULT_IV_RANGES = listOf(IvRange(0, 9, 20.0), IvRange(10, 19, 45.0), IvRange(20, 29, 25.0), IvRange(30, 31, 10.0))
        val DEFAULT_TIPS = listOf(
            "포켓몬 회복은 광장의 간호순에게 말을 걸면 됩니다. 광장은 /광장 들어가기나 ESC 메뉴 왼쪽 위 '광장 가기' 버튼으로 갈 수 있습니다.",
            "광장에서 돌아갈 때는 /광장 나가기나 ESC 메뉴의 '광장 나가기'를 누르세요. 들어오기 전 자리로 돌아갑니다.",
            "관장 도전은 광장의 리그 안내원에게 말을 걸어 시작합니다. 리그 안내원은 광장 도착 지점에서 북동쪽에 있습니다.",
            "리그 챔피언이 되면 광장의 타워 안내원과 팩토리 안내원에게서 배틀타워와 배틀팩토리에 도전할 수 있습니다.",
            "레벨캡은 16에서 시작해 관장을 이길 때마다 오르고, 챔피언을 이기면 80이 됩니다. 레벨캡보다 높은 포켓몬은 잡을 수 없고 배틀에도 못 냅니다.",
            "채팅 이름 앞의 볼은 리그 등급입니다. 배지 3개면 슈퍼볼, 5개면 하이퍼볼, 8개면 마스터볼, 챔피언이 되면 챔피언 등급이 됩니다.",
            "야생 포켓몬 레벨은 구역마다 다르고 내 레벨캡보다 낮게 나옵니다. 지금 서 있는 구역의 레벨은 /구역스폰레벨로 확인하세요.",
            "포켓몬은 포켓몬끼리 배틀해서 이겨야 경험치를 받습니다. 플레이어가 직접 때려잡으면 레벨이 오르지 않습니다.",
            "몬스터볼은 나무에 열리는 규토리로 만듭니다. 아이템 위에 마우스를 올리고 R을 누르면 제작법, U를 누르면 쓰는 곳이 나옵니다.",
            "/포켓내비나 Tab 키로 포켓내비를 열면 주변 포켓몬의 스폰 정보를 볼 수 있습니다. 열린 상태에서 Tab을 다시 누르면 닫힙니다.",
            "포켓몬의 개체값과 능력치는 M 키로 정보 화면을 열거나, 꺼내 둔 내 포켓몬을 우클릭해서 볼 수 있습니다.",
            "왼쪽 파티 목록이 사라졌다면 O 키를 눌러 다시 켜세요.",
            "야생 트레이너, 배틀 타워, 배틀 팩토리, 리그 챌린지에서 이기면 BP를 받습니다. 모은 BP는 광장의 상점 주인이나 /mcc의 상점 탭에서 씁니다.",
            "/mcc의 PvP 탭에서 다른 트레이너와 대전할 수 있습니다.",
            "전설 포켓몬이 나타나면 채팅으로 알려 줍니다. 전설은 불러낸 트레이너만 배틀하고 잡을 수 있습니다.",
            "이미 잡은 전설은 내 주변에 다시 나오지 않습니다. /legends <포켓몬>으로 잡았는지 확인할 수 있습니다.",
            "/마이룸 들어가기로 나만의 방에 가고, /마이룸 나가기로 원래 자리에 돌아옵니다. 다른 사람 방은 /마이룸 들어가기 <닉네임>으로 놀러 갑니다.",
            "/마이룸 공개, /마이룸 비공개로 방 출입을 정하고, /마이룸 권한허용 <닉네임>으로 친구에게 건축을 맡길 수 있습니다.",
            "/마이룸 상시활성 켜기를 해 두면 방에 없을 때도 작물, 화로, 호퍼, 목장이 돌아갑니다.",
            "/마이룸만 입력하면 내 방 정보와 마이룸 명령어 전체를 볼 수 있습니다.",
            "버그 제보나 건의는 /문의 뒤에 내용을 함께 써서 보내세요. 예: /문의 이로치를 잡았는데 사라졌어요",
            "디스코드는 ESC 메뉴 왼쪽 위 '빡켓몬 디스코드' 버튼으로 들어올 수 있습니다. 들어온 뒤 게임에서 /디코인증을 입력하고, 받은 코드를 디스코드에서 /verify로 입력하세요.",
            "디스코드에서 /피츄 <질문>을 입력하면 피츄가 서버에 대해 답해 줍니다.",
            "서버 위키는 ESC 메뉴 왼쪽 위 '빡켓몬 위키' 버튼이나 /위키로 열 수 있습니다. 내 진행도에 맞춘 정보도 함께 보입니다.",
            "/웨이포인트로 지금 위치를 지도에 저장할 수 있습니다. 뒤에 이름을 붙여도 됩니다.",
            "/nick set <닉네임>으로 채팅과 접속자 목록에 보이는 닉네임을 바꿀 수 있습니다.",
            "/release <슬롯>으로 PC를 켜지 않고 파티 포켓몬을 놓아줄 수 있습니다.",
            "/poketoitem으로 포켓몬을 아이템으로 바꾸고, /itemtopoke로 다시 포켓몬으로 되돌릴 수 있습니다.",
            "/pokefusion으로 같은 진화 계보의 포켓몬을 합성하면 더 높은 개체값을 이어받습니다.",
            "음악이 안 나오면 ESC → 설정 → 음악 및 소리에서 음악 볼륨을 올려 보세요.",
            "조작 설정에서 이모트 키를 지정하면, 그 키를 누른 채 방향을 골라 머리 위에 이모트를 띄울 수 있습니다.",
            "렉이 심하면 ESC → 모드 → Rounding-Block 설정에서 '둥근 블록 렌더링 사용'을 끄거나, /roundingblock enabled false를 입력해 보세요.",
            "셰이더를 켠 상태에서 렉이 심하면 ESC → 설정 → 비디오 설정 → 셰이더 팩에서 셰이더를 꺼 보세요.",
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
                root.get("wildHiddenAbilityOneIn")?.asInt ?: defaults.wildHiddenAbilityOneIn,
                iv?.get("enabled")?.asBoolean ?: defaults.wildIvEnabled,
                iv?.getAsJsonArray("ranges")?.map { element ->
                    val range = element.asJsonObject
                    IvRange(range.get("min").asInt, range.get("max").asInt, range.get("chance").asDouble)
                } ?: defaults.wildIvRanges,
                root.get("tipIntervalSeconds")?.asInt ?: defaults.tipIntervalSeconds,
                root.getAsJsonArray("tips")?.map { it.asString } ?: defaults.tips,
                plazaOtherHubDimensions = root.getAsJsonObject("plaza")?.getAsJsonArray("otherHubDimensions")
                    ?.map { it.asString.trim().lowercase() }?.filter { it.isNotEmpty() } ?: defaults.plazaOtherHubDimensions,
            )
        }

        fun toJson(config: PolicyConfig): String = gson.toJson(JsonObject().apply {
            add("plaza", JsonObject().apply {
                addProperty("x", config.plaza.x); addProperty("y", config.plaza.y); addProperty("z", config.plaza.z)
                addProperty("yaw", config.plaza.yaw); addProperty("pitch", config.plaza.pitch)
                add("otherHubDimensions", gson.toJsonTree(config.plazaOtherHubDimensions))
            })
            addProperty("wildHiddenAbilityOneIn", config.wildHiddenAbilityOneIn)
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
