package jbro.cobblemon.mcc.internal.wiki

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.loader.api.FabricLoader

/**
 * The server wiki's settings, from `config/more-cobblemon-contents/wiki.json`. Off until an admin turns it on, since
 * it opens a port. [publicUrl] is the address players' browsers reach the wiki at; the server cannot know it
 * behind a host or proxy, so it is set here and falls back to this machine on [port].
 */
internal data class WikiConfig(
    val enabled: Boolean = false,
    val bind: String = "0.0.0.0",
    val port: Int = 8100,
    val publicUrl: String = "",
    val directory: String = "config/more-cobblemon-contents/wiki",
) {
    init {
        require(port in 1..65535) { "port must be 1-65535" }
    }

    /** The base URL links point to, without a trailing slash. */
    val base: String get() = publicUrl.trimEnd('/').ifEmpty { "http://localhost:$port" }

    companion object {
        fun read(json: String): WikiConfig {
            val root = JsonParser.parseString(json).asJsonObject
            val defaults = WikiConfig()
            return WikiConfig(
                enabled = root.get("enabled")?.asBoolean ?: defaults.enabled,
                bind = root.get("bind")?.asString ?: defaults.bind,
                port = root.get("port")?.asInt ?: defaults.port,
                publicUrl = root.get("public_url")?.asString ?: defaults.publicUrl,
                directory = root.get("directory")?.asString ?: defaults.directory,
            )
        }

        fun write(config: WikiConfig): String = GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply {
            addProperty("enabled", config.enabled)
            addProperty("bind", config.bind)
            addProperty("port", config.port)
            addProperty("public_url", config.publicUrl)
            addProperty("directory", config.directory)
        }) + "\n"

        /** Reads the file, writing the defaults when it is missing; a broken file keeps the wiki off. */
        fun load(path: Path = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents").resolve("wiki.json")): WikiConfig = try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.parent)
                Files.writeString(path, write(WikiConfig()))
                WikiConfig()
            } else {
                read(Files.readString(path))
            }
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Wiki config {} could not be read; the wiki stays off", path, failure)
            WikiConfig(enabled = false)
        }
    }
}
