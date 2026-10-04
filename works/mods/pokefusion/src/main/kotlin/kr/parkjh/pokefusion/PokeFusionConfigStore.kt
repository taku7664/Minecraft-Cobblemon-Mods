package kr.parkjh.pokefusion

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class PokeFusionConfig(val commandPermissionLevel: Int = DEFAULT_PERMISSION_LEVEL) {
    companion object {
        const val DEFAULT_PERMISSION_LEVEL = 0
        const val ERROR_PERMISSION_LEVEL = 4
        const val MIN_PERMISSION_LEVEL = 0
        const val MAX_PERMISSION_LEVEL = 4
    }
}

class PokeFusionConfigStore(private val configDirectory: Path) {
    private val configPath = configDirectory.resolve(FILE_NAME)

    fun load(): PokeFusionConfig {
        return try {
            Files.createDirectories(configDirectory)
            if (Files.notExists(configPath)) {
                return PokeFusionConfig().also(::save)
            }
            val root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8))
            if (!root.isJsonObject) return fallback("설정의 최상위 값은 객체여야 합니다.")
            val permission = root.asJsonObject.get("commandPermissionLevel")
                ?: return fallback("commandPermissionLevel 필드가 없습니다.")
            if (!permission.isJsonPrimitive || !permission.asJsonPrimitive.isNumber) {
                return fallback("commandPermissionLevel은 정수여야 합니다.")
            }
            val level = try {
                permission.asBigDecimal.intValueExact()
            } catch (_: ArithmeticException) {
                return fallback("commandPermissionLevel은 정수여야 합니다.")
            }
            if (level !in PokeFusionConfig.MIN_PERMISSION_LEVEL..PokeFusionConfig.MAX_PERMISSION_LEVEL) {
                fallback("commandPermissionLevel은 0~4만 사용할 수 있습니다.")
            } else {
                PokeFusionConfig(level)
            }
        } catch (exception: Exception) {
            LOGGER.error(
                "Pokefusion 설정을 읽지 못해 안전 권한 레벨 4를 사용합니다: {} ({})",
                configPath,
                exception.message ?: exception.javaClass.simpleName
            )
            failClosed()
        }
    }

    fun save(config: PokeFusionConfig) {
        require(config.commandPermissionLevel in PokeFusionConfig.MIN_PERMISSION_LEVEL..PokeFusionConfig.MAX_PERMISSION_LEVEL) {
            "commandPermissionLevel은 0~4만 사용할 수 있습니다."
        }
        Files.createDirectories(configDirectory)
        writeAtomically(config)
    }

    private fun fallback(reason: String): PokeFusionConfig {
        LOGGER.error("Pokefusion 설정이 올바르지 않아 안전 권한 레벨 4를 사용합니다: {} ({})", configPath, reason)
        return failClosed()
    }

    private fun failClosed() = PokeFusionConfig(PokeFusionConfig.ERROR_PERMISSION_LEVEL)

    private fun writeAtomically(config: PokeFusionConfig) {
        val temporary = Files.createTempFile(configDirectory, ".pokefusion.", ".tmp")
        try {
            Files.writeString(temporary, GSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val FILE_NAME = "pokefusion.json"
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val LOGGER = LoggerFactory.getLogger(PokeFusionConfigStore::class.java)
    }
}
