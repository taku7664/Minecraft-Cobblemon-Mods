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

    /** Whether only the caller sees the reply. */
    val ephemeral: Boolean get() = false

    /** Whether it works outside the command channel, as `/verify` must in the verify channel. */
    val anyChannel: Boolean get() = false

    /** The reply message body (content or embeds) to [options] given by name. Runs on the server thread. */
    fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject

    /** [reply] for a command that needs to know who ran it. */
    fun reply(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject = reply(server, options)

    fun definition() = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        addProperty("type", 1)
        if (options.size() > 0) add("options", options)
    }
}

/** Who ran an operator command, as Discord tells it. */
internal data class DiscordCaller(
    val userId: String,
    val userName: String,
    val roleIds: List<String>,
    val channelId: String,
    val guildId: String = "",
) {
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

    /** Why a player's command cannot run in [channelId], or null when it can. */
    fun channelRefusal(settings: DiscordSettings, command: DiscordCommand, channelId: String): String? = when {
        command is DiscordAdminCommand || command.anyChannel -> null
        settings.commandChannelId.isBlank() || channelId == settings.commandChannelId -> null
        else -> DiscordText.say("commandChannel", "channel" to settings.commandChannelId)
    }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun check(settings: DiscordSettings, caller: DiscordCaller, command: String): Verdict = when {
        settings.adminChannelId.isBlank() -> Verdict.Refused(DiscordText.say("adminNoChannel"))
        caller.channelId != settings.adminChannelId -> Verdict.Refused(DiscordText.say("adminWrongChannel"))
        (listOf(caller.userId) + caller.roleIds).none { id ->
            settings.adminAccess[id]?.let { ALL in it || command in it } == true
        } -> Verdict.Refused(DiscordText.say("adminNoPermission", "command" to command))
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
        override val description get() = DiscordText.say("onlineDescription")
        override val ephemeral = true

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject {
            // Players know each other by their Styled Nicknames nickname, which is the display name.
            val names = server.playerList.players
                .map { player -> KoreanText.render(player.displayName ?: player.name).ifBlank { player.gameProfile.name } }
                .sortedBy { it.lowercase() }
            val text = if (names.isEmpty()) DiscordText.say("onlineNone") else DiscordText.say("onlineSome", "count" to names.size, "names" to names.joinToString(", "))
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
