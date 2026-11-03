package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import com.mojang.brigadier.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.policy.api.Announcements
import jbro.cobblemon.policy.legend.SpawnForCommand
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSource
import net.minecraft.commands.arguments.item.ItemArgument
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.players.UserBanListEntry
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource

/**
 * The bot's operator commands: notices, the player list, bans, items, Pokemon and the console. Battle Points come
 * from [MccDiscordCommands] since they live in More Cobblemon Contents. Each takes players by account name or UUID,
 * online or not, except a Pokemon, which needs a player to spawn next to.
 */
internal object DiscordAdminCommands {
    private const val PAGE_SIZE = 20
    // One /give sends at most a full inventory.
    private const val MAX_ITEMS = 36 * 64
    private const val CODE_BLOCK_LIMIT = 1900

    fun register() {
        listOf(announce, players, ban, unban, give, spawn, console).forEach(DiscordCommands::add)
    }

    private fun playerOption() = DiscordCommands.stringOption("player", DiscordText.say("adminPlayerOption"))

    /** The player [asked] for by account name or UUID, among those online and those who ever joined. */
    fun profile(server: MinecraftServer, asked: String): GameProfile? {
        val text = asked.trim()
        val uuid = runCatching { UUID.fromString(text) }.getOrNull()
        if (uuid != null) {
            return server.playerList.getPlayer(uuid)?.gameProfile ?: server.profileCache?.get(uuid)?.orElse(null)
        }
        return server.playerList.getPlayerByName(text)?.gameProfile ?: server.profileCache?.get(text)?.orElse(null)
    }

    fun unknown(asked: String) = DiscordRest.message(DiscordText.say("adminUnknownPlayer", "asked" to asked))

    private val announce = object : DiscordAdminCommand {
        override val name = "announce"
        override val description get() = DiscordText.say("announceDescription")
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("message", DiscordText.say("announceOption"))) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val message = options["message"].orEmpty().trim()
            if (message.isEmpty()) return DiscordRest.message(DiscordText.say("announceEmpty"))
            Announcements.broadcast(server, Component.literal(message))
            return DiscordRest.message(DiscordText.say("announceDone", "message" to message))
        }
    }

    private val players = object : DiscordAdminCommand {
        override val name = "players"
        override val description get() = DiscordText.say("playersDescription")
        override val options = JsonArray().apply { add(DiscordCommands.integerOption("page", DiscordText.say("playersPageOption"), 1, required = false)) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val everyone = known(server)
            val pages = maxOf(1, (everyone.size + PAGE_SIZE - 1) / PAGE_SIZE)
            val page = (options["page"]?.toIntOrNull() ?: 1).coerceIn(1, pages)
            val lines = everyone.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).map { (profile, nickname) ->
                val online = DiscordText.say(if (server.playerList.getPlayer(profile.id) != null) "playersOnline" else "playersOffline")
                "$online ${profile.name} | ${nickname ?: "-"} | ${profile.id}"
            }
            val header = DiscordText.say("playersHeader", "count" to everyone.size, "page" to page, "pages" to pages)
            return DiscordRest.message(header + "\n```\n" + lines.joinToString("\n").take(CODE_BLOCK_LIMIT) + "\n```")
        }
    }

    /** Everyone who ever joined, by account name, with their nickname when they set one. */
    private fun known(server: MinecraftServer): List<Pair<GameProfile, String?>> {
        val ids = LinkedHashSet<UUID>()
        server.playerList.players.forEach { ids += it.uuid }
        val playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
        if (Files.isDirectory(playerData)) Files.list(playerData).use { files ->
            files.forEach { file ->
                val name = file.fileName.toString()
                if (name.endsWith(".dat")) runCatching { UUID.fromString(name.removeSuffix(".dat")) }.getOrNull()?.let { ids += it }
            }
        }
        return ids.map { id ->
            val online = server.playerList.getPlayer(id)
            val profile = online?.gameProfile ?: server.profileCache?.get(id)?.orElse(null) ?: GameProfile(id, "?")
            val nickname = online?.displayName?.let { KoreanText.render(it) }?.takeIf { it != profile.name } ?: savedNickname(server, id)
            profile to nickname
        }.sortedBy { it.first.name.lowercase() }
    }

    /** Styled Nicknames keeps each nickname, with its formatting tags, in the player's mod data. */
    private fun savedNickname(server: MinecraftServer, id: UUID): String? {
        val file: Path = server.getWorldPath(LevelResource.ROOT).resolve("player-mod-data").resolve(id.toString()).resolve("general.dat")
        if (!Files.exists(file)) return null
        val raw = runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getString("stylednicknames:nickname") }.getOrNull()
        return raw?.replace(Regex("<[^>]*>"), "")?.trim()?.ifEmpty { null }
    }

    private val ban = object : DiscordAdminCommand {
        override val name = "ban"
        override val description get() = DiscordText.say("banDescription")
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("reason", DiscordText.say("banReasonOption"), required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val bans = server.playerList.bans
            if (bans.isBanned(profile)) return DiscordRest.message(DiscordText.say("banAlready", "player" to profile.name))
            val reason = options["reason"]?.trim()?.ifEmpty { null }
            bans.add(UserBanListEntry(profile, null, "Discord: ${caller.userName}", null, reason))
            server.playerList.getPlayer(profile.id)?.connection?.disconnect(Component.translatable("multiplayer.disconnect.banned"))
            return DiscordRest.message(DiscordText.say("banDone", "player" to profile.name, "reason" to (reason?.let { DiscordText.say("banReason", "reason" to it) } ?: "")))
        }
    }

    private val unban = object : DiscordAdminCommand {
        override val name = "unban"
        override val description get() = DiscordText.say("unbanDescription")
        override val options = JsonArray().apply { add(playerOption()) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val bans = server.playerList.bans
            if (!bans.isBanned(profile)) return DiscordRest.message(DiscordText.say("unbanNotBanned", "player" to profile.name))
            bans.remove(profile)
            return DiscordRest.message(DiscordText.say("unbanDone", "player" to profile.name))
        }
    }

    private val give = object : DiscordAdminCommand {
        override val name = "give"
        override val description get() = DiscordText.say("giveDescription")
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("item", DiscordText.say("giveItemOption")))
            add(DiscordCommands.integerOption("count", DiscordText.say("giveCountOption"), 1, required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val count = options["count"]?.toIntOrNull() ?: 1
            if (count !in 1..MAX_ITEMS) return DiscordRest.message(DiscordText.say("giveBadCount", "max" to MAX_ITEMS))
            val item = options["item"].orEmpty().trim()
            val one = try {
                ItemArgument(CommandBuildContext.simple(server.registryAccess(), server.worldData.enabledFeatures()))
                    .parse(StringReader(item)).createItemStack(1, false)
            } catch (failure: Exception) {
                return DiscordRest.message(DiscordText.say("giveUnknownItem", "item" to item, "reason" to failure.message.orEmpty()))
            }
            val stacks = generateSequence(count) { left -> (left - one.maxStackSize).takeIf { it > 0 } }
                .map { left -> one.copyWithCount(minOf(left, one.maxStackSize)) }.toList()
            val itemName = KoreanText.render(one.hoverName)
            val online = server.playerList.getPlayer(profile.id)
            if (online != null) {
                PendingItems.hand(online, stacks)
                return DiscordRest.message(DiscordText.say("giveDone", "player" to profile.name, "item" to itemName, "count" to count))
            }
            PendingItems.get(server).add(profile.id, stacks)
            return DiscordRest.message(DiscordText.say("giveLater", "player" to profile.name, "item" to itemName, "count" to count))
        }
    }

    private val spawn = object : DiscordAdminCommand {
        override val name = "spawn"
        override val description get() = DiscordText.say("spawnDescription")
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("pokemon", DiscordText.say("spawnOption")))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val player = server.playerList.getPlayer(profile.id)
                ?: return DiscordRest.message(DiscordText.say("spawnOffline", "player" to profile.name))
            val text = options["pokemon"].orEmpty().trim()
            val properties = PokemonProperties.parse(text)
            if (properties.species == null) return DiscordRest.message(DiscordText.say("spawnUnknown", "text" to text))
            val spawned = SpawnForCommand.spawn(player, properties) ?: return DiscordRest.message(DiscordText.say("spawnFailed"))
            val lines = listOf(DiscordText.say("spawnDone", "player" to profile.name, "species" to KoreanText.render(spawned.entity.pokemon.species.translatedName),
                "level" to spawned.entity.pokemon.level)) + spawned.warnings.map { DiscordText.say("spawnWarning", "warning" to KoreanText.render(it)) }
            return DiscordRest.message(lines.joinToString("\n"))
        }
    }

    private val console = object : DiscordAdminCommand {
        override val name = "console"
        override val description get() = DiscordText.say("consoleDescription")
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("command", DiscordText.say("consoleOption"))) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val command = options["command"].orEmpty().trim().removePrefix("/")
            if (command.isEmpty()) return DiscordRest.message(DiscordText.say("consoleEmpty"))
            val shown = runCommand(server, command).replace("```", "'''")
            return DiscordRest.message("`/$command`\n```\n${shown.take(CODE_BLOCK_LIMIT)}\n```")
        }
    }

    /** Runs [command] as the console and returns what it answered. Call on the server thread. */
    fun runCommand(server: MinecraftServer, command: String): String {
        val output = mutableListOf<String>()
        val capture = object : CommandSource {
            override fun sendSystemMessage(message: Component) { output += KoreanText.render(message) }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = true
        }
        server.commands.performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command)
        return output.joinToString("\n").ifBlank { DiscordText.say("consoleNoOutput") }
    }
}
