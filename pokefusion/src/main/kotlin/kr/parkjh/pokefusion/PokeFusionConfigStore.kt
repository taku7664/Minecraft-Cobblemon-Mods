package kr.parkjh.pokefusion

import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class PokeFusionConfig(val commandPermissionLevel: Int = DEFAULT_PERMISSION_LEVEL) {
    companion object {
        const val DEFAULT_PERMISSION_LEVEL = 0
        const val MIN_PERMISSION_LEVEL = 0
        const val MAX_PERMISSION_LEVEL = 4
    }
}

class PokeFusionConfigStore(private val configDirectory: Path) {
    private val configPath = configDirectory.resolve(FILE_NAME)

    fun load(): PokeFusionConfig {
        return try {
            Files.createDirectories(configDirectory)
            if (Files.notExists(configPath)) writeDefault()
            val parsed = GSON.fromJson(Files.readString(configPath, StandardCharsets.UTF_8), PokeFusionConfig::class.java)
                ?: return fallback("설정 내용이 비어 있습니다.")
            if (parsed.commandPermissionLevel !in PokeFusionConfig.MIN_PERMISSION_LEVEL..PokeFusionConfig.MAX_PERMISSION_LEVEL) {
                fallback("commandPermissionLevel은 0~4만 사용할 수 있습니다.")
            } else {
                parsed
            }
        } catch (exception: Exception) {
            LOGGER.error(
                "Pokefusion 설정을 읽지 못해 기본 권한 레벨 0을 사용합니다: {} ({})",
                configPath,
                exception.message ?: exception.javaClass.simpleName
            )
            PokeFusionConfig()
        }
    }

    private fun fallback(reason: String): PokeFusionConfig {
        LOGGER.error("Pokefusion 설정이 올바르지 않아 기본 권한 레벨 0을 사용합니다: {} ({})", configPath, reason)
        return PokeFusionConfig()
    }

    private fun writeDefault() {
        val temporary = Files.createTempFile(configDirectory, ".pokefusion.", ".tmp")
        try {
            Files.writeString(temporary, GSON.toJson(PokeFusionConfig()) + System.lineSeparator(), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, configPath)
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
