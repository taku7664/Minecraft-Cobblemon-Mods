package jbro.cobblemon.customspecies

import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object CustomSpeciesConfigFile {
    val path: Path
        get() = FabricLoader.getInstance().configDir
            .resolve("cobblemon-custom-species")
            .resolve("species-overrides.json")

    fun readOrCreate(): String {
        val configPath = path
        Files.createDirectories(configPath.parent)
        if (Files.notExists(configPath)) {
            runCatching {
                Files.writeString(
                    configPath,
                    DEFAULT_CONFIG,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
            }.getOrElse { error ->
                if (Files.notExists(configPath)) throw error
            }
        }
        return Files.readString(configPath, StandardCharsets.UTF_8)
    }

    private val DEFAULT_CONFIG = """
        {
          "schema": 1,
          "overrides": []
        }
        """.trimIndent() + System.lineSeparator()
}
