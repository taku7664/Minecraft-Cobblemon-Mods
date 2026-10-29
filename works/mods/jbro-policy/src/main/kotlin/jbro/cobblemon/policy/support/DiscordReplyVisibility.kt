package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Small, reload-on-command setting kept apart from bot credentials. */
internal class DiscordReplyVisibility(
    private val file: Path,
    private val warn: (String, Throwable) -> Unit,
) {
    private var lastText: String? = null
    private var onlinePlayersPrivate = true

    init {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, "{\n  \"onlinePlayersPrivate\": true\n}\n", StandardOpenOption.CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            // Keep the operator's existing choice.
        } catch (failure: Exception) {
            warn("Could not create Discord reply visibility config at $file", failure)
        }
    }

    @Synchronized
    fun isPrivate(commandName: String?, commandDefault: Boolean): Boolean {
        if (commandName != "접속자") return commandDefault
        val text = try {
            Files.readString(file)
        } catch (failure: Exception) {
            warn("Could not read Discord reply visibility config at $file", failure)
            return onlinePlayersPrivate
        }
        if (text != lastText) {
            lastText = text
            try {
                val value = JsonParser.parseString(text).asJsonObject.get("onlinePlayersPrivate")
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                    "onlinePlayersPrivate must be true or false"
                }
                onlinePlayersPrivate = value.asBoolean
            } catch (failure: Exception) {
                warn("Invalid Discord reply visibility config at $file; keeping the last valid value", failure)
            }
        }
        return onlinePlayersPrivate
    }
}
