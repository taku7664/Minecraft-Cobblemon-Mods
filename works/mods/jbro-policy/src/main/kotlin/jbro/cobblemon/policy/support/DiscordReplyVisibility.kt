package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Who sees a bot command's reply, read at each command and kept apart from bot credentials. `commands` maps a command's
 * name to whether its reply is private; a command it leaves out keeps its own default.
 */
internal class DiscordReplyVisibility(
    private val file: Path,
    private val warn: (String, Throwable) -> Unit,
) {
    private var lastText: String? = null
    private var onlinePlayersPrivate = true
    private var commands = emptyMap<String, Boolean>()

    init {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, "{\n  \"onlinePlayersPrivate\": true,\n  \"commands\": {}\n}\n", StandardOpenOption.CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            // Keep the operator's existing choice.
        } catch (failure: Exception) {
            warn("Could not create Discord reply visibility config at $file", failure)
        }
    }

    @Synchronized
    fun isPrivate(commandName: String?, commandDefault: Boolean): Boolean {
        reload()
        return commands[commandName] ?: if (commandName == "접속자") onlinePlayersPrivate else commandDefault
    }

    private fun reload() {
        val text = try {
            Files.readString(file)
        } catch (failure: Exception) {
            warn("Could not read Discord reply visibility config at $file", failure)
            return
        }
        if (text == lastText) return
        lastText = text
        try {
            val root = JsonParser.parseString(text).asJsonObject
            val online = root.get("onlinePlayersPrivate")
            require(online == null || (online.isJsonPrimitive && online.asJsonPrimitive.isBoolean)) {
                "onlinePlayersPrivate must be true or false"
            }
            val edited = root.getAsJsonObject("commands")?.entrySet().orEmpty().associate { (name, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "commands.$name must be true or false" }
                name to value.asBoolean
            }
            if (online != null) onlinePlayersPrivate = online.asBoolean
            commands = edited
        } catch (failure: Exception) {
            warn("Invalid Discord reply visibility config at $file; keeping the last valid value", failure)
        }
    }
}
