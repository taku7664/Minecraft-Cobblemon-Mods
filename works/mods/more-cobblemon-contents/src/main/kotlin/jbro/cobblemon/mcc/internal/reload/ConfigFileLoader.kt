package jbro.cobblemon.mcc.internal.reload

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.reload.MccReloadResult

/** A parsed config file, whether it lacked entries it should be rewritten with, and the mistakes found in it. */
internal data class ConfigRead<T>(val config: T, val incomplete: Boolean, val problems: List<String>)

/** The config a load leaves in use, and what the load did. */
internal data class ConfigLoad<T>(val config: T, val result: MccReloadResult)

/**
 * Loads one JSON config file under the rules every MCC config file shares. A missing file is written from [defaults].
 * A file that does not parse, or has mistakes, keeps [previous] in use and is left for the admin to fix; with no
 * [previous] (the first load) a parse failure uses [defaults] and mistakes use what [read] made of the file. A file
 * that only lacks entries gets them written in.
 */
internal fun <T> loadConfigFile(
    path: Path,
    file: String,
    previous: T?,
    defaults: () -> T,
    write: (T) -> String,
    read: (String) -> ConfigRead<T>,
): ConfigLoad<T> {
    val parsed = try {
        if (Files.notExists(path)) {
            val created = defaults()
            save(path, write(created))
            return ConfigLoad(created, MccReloadResult.Applied(file, listOf("created with the defaults")))
        }
        read(Files.readString(path))
    } catch (failure: Exception) {
        MoreCobblemonContents.LOGGER.error("Config {} could not be read; keeping the values in use", path, failure)
        return ConfigLoad(previous ?: defaults(), MccReloadResult.Kept(file, failure.message ?: failure.javaClass.simpleName))
    }
    parsed.problems.forEach { MoreCobblemonContents.LOGGER.warn("Config {}: {}", path, it) }
    if (parsed.problems.isNotEmpty()) {
        return if (previous != null) {
            ConfigLoad(previous, MccReloadResult.Kept(file, parsed.problems.joinToString("; ")))
        } else {
            ConfigLoad(parsed.config, MccReloadResult.Applied(file, parsed.problems))
        }
    }
    if (parsed.incomplete) {
        return try {
            save(path, write(parsed.config))
            ConfigLoad(parsed.config, MccReloadResult.Applied(file, listOf("missing entries were added with their defaults")))
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Config {} could not be completed", path, failure)
            ConfigLoad(parsed.config, MccReloadResult.Applied(file, listOf("missing entries use their defaults; the file could not be rewritten")))
        }
    }
    return ConfigLoad(parsed.config, MccReloadResult.Applied(file))
}

private fun save(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
}
