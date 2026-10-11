package kr.parkjh.pokefusion

import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory

/**
 * 합성 화면 문구는 서버가 보내고 번역은 클라이언트가 푼다. 클라이언트에 이 모드가 없거나
 * 옛 버전이면 새 키가 그대로 보이므로, 모드에 든 한국어 문구를 대체 텍스트로 함께 보낸다.
 */
object PokeFusionText {
    private val LOGGER = LoggerFactory.getLogger(PokeFusionText::class.java)

    private val fallback: Map<String, String> by lazy {
        try {
            PokeFusionText::class.java.getResourceAsStream("/assets/pokefusion/lang/ko_kr.json")
                ?.reader(Charsets.UTF_8)
                ?.use { reader ->
                    JsonParser.parseReader(reader).asJsonObject.entrySet().associate { it.key to it.value.asString }
                }
                ?: emptyMap()
        } catch (exception: Exception) {
            LOGGER.warn("Pokefusion 대체 문구를 읽지 못했습니다. 번역 키만 보냅니다.", exception)
            emptyMap()
        }
    }

    fun of(key: String, vararg arguments: Any): MutableComponent =
        Component.translatableWithFallback(key, fallback[key], *arguments)
}
