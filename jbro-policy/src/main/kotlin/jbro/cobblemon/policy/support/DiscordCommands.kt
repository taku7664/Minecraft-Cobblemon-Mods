package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.server.MinecraftServer

/** One slash command of the bot: its definition for Discord, and its reply, worked out on the server thread. */
internal interface DiscordCommand {
    val name: String
    val description: String

    /** Options as Discord defines them; empty for a command that takes none. */
    val options: JsonArray get() = JsonArray()

    /** The reply message body (content or embeds) to [options] given by name. Runs on the server thread. */
    fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject

    fun definition() = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        addProperty("type", 1)
        if (options.size() > 0) add("options", options)
    }
}

/**
 * The bot's slash commands. jbro-policy brings /접속자; More Cobblemon Contents, when installed, adds the commands
 * that read its records ([MccDiscordCommands]).
 */
internal object DiscordCommands {
    private val commands = LinkedHashMap<String, DiscordCommand>()

    fun add(command: DiscordCommand) {
        commands[command.name] = command
    }

    fun find(name: String): DiscordCommand? = commands[name]

    fun definitions(): JsonArray = JsonArray().apply { commands.values.forEach { add(it.definition()) } }

    fun registerBuiltIns() = add(object : DiscordCommand {
        override val name = "접속자"
        override val description = "지금 서버에 접속한 트레이너"

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            val names = server.playerList.players.map { it.gameProfile.name }.sortedBy { it.lowercase() }
            val text = if (names.isEmpty()) "지금은 접속한 트레이너가 없어요." else "지금 ${names.size}명 접속 중이에요.\n" + names.joinToString(", ")
            return DiscordRest.message(text)
        }
    })

    /** A string option for [definition]s. */
    fun stringOption(name: String, description: String, choices: List<Pair<String, String>> = emptyList()) = JsonObject().apply {
        addProperty("type", 3)
        addProperty("name", name)
        addProperty("description", description)
        addProperty("required", true)
        if (choices.isNotEmpty()) add("choices", JsonArray().apply {
            choices.forEach { (label, value) -> add(JsonObject().apply { addProperty("name", label); addProperty("value", value) }) }
        })
    }
}
