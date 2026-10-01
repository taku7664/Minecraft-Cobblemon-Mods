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

/** Who ran an operator command, as Discord tells it. */
internal data class DiscordCaller(val userId: String, val userName: String, val roleIds: List<String>, val channelId: String) {
    override fun toString() = "$userName ($userId)"
}

/**
 * An operator command. Discord shows it only to server administrators unless a role is let in under the server's
 * Integrations settings, and the bot still runs it only in the admin channel for the IDs that `adminAccess` lets in.
 * Names stay ASCII, so they type the same on every keyboard.
 */
internal interface DiscordAdminCommand : DiscordCommand {
    fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject

    override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject =
        error("/$name needs its caller")

    override fun definition() = super.definition().apply {
        // "0": nobody but administrators until the server grants it; guild channels only.
        addProperty("default_member_permissions", "0")
        add("contexts", JsonArray().apply { add(0) })
    }
}

/** Whether a caller may run an operator command; kept apart from the bot so it can be tested. */
internal object DiscordAdminAccess {
    const val ALL = "*"

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun check(settings: DiscordSettings, caller: DiscordCaller, command: String): Verdict = when {
        settings.adminChannelId.isBlank() -> Verdict.Refused("관리자 채널이 설정되지 않아 관리자 명령이 꺼져 있어요.")
        caller.channelId != settings.adminChannelId -> Verdict.Refused("관리자 명령은 관리자 채널에서만 쓸 수 있어요.")
        (listOf(caller.userId) + caller.roleIds).none { id ->
            settings.adminAccess[id]?.let { ALL in it || command in it } == true
        } -> Verdict.Refused("/$command 명령을 쓸 권한이 없어요.")
        else -> Verdict.Allowed
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
    fun stringOption(name: String, description: String, choices: List<Pair<String, String>> = emptyList(), required: Boolean = true) = JsonObject().apply {
        addProperty("type", 3)
        addProperty("name", name)
        addProperty("description", description)
        addProperty("required", required)
        if (choices.isNotEmpty()) add("choices", JsonArray().apply {
            choices.forEach { (label, value) -> add(JsonObject().apply { addProperty("name", label); addProperty("value", value) }) }
        })
    }

    /** A whole-number option for [definition]s, at least [min]. */
    fun integerOption(name: String, description: String, min: Long, required: Boolean = true) = JsonObject().apply {
        addProperty("type", 4)
        addProperty("name", name)
        addProperty("description", description)
        addProperty("required", required)
        addProperty("min_value", min)
    }
}
