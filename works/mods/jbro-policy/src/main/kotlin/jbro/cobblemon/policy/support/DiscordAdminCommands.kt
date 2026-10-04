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

    private fun playerOption() = DiscordCommands.stringOption("player", "마인크래프트 아이디 또는 UUID")

    /** The player [asked] for by account name or UUID, among those online and those who ever joined. */
    fun profile(server: MinecraftServer, asked: String): GameProfile? {
        val text = asked.trim()
        val uuid = runCatching { UUID.fromString(text) }.getOrNull()
        if (uuid != null) {
            return server.playerList.getPlayer(uuid)?.gameProfile ?: server.profileCache?.get(uuid)?.orElse(null)
        }
        return server.playerList.getPlayerByName(text)?.gameProfile ?: server.profileCache?.get(text)?.orElse(null)
    }

    fun unknown(asked: String) = DiscordRest.message("'$asked' 플레이어를 찾지 못했어요. 서버에 한 번이라도 접속한 아이디나 UUID인지 확인해 주세요.")

    private val announce = object : DiscordAdminCommand {
        override val name = "announce"
        override val description = "게임 안에 [공지]를 띄운다"
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("message", "공지 내용")) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val message = options["message"].orEmpty().trim()
            if (message.isEmpty()) return DiscordRest.message("공지 내용이 비어 있어요.")
            Announcements.broadcast(server, Component.literal(message))
            return DiscordRest.message("공지를 띄웠어요: $message")
        }
    }

    private val players = object : DiscordAdminCommand {
        override val name = "players"
        override val description = "서버에 접속한 적 있는 모든 플레이어의 아이디·닉네임·UUID"
        override val options = JsonArray().apply { add(DiscordCommands.integerOption("page", "쪽 번호 (기본 1)", 1, required = false)) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val everyone = known(server)
            val pages = maxOf(1, (everyone.size + PAGE_SIZE - 1) / PAGE_SIZE)
            val page = (options["page"]?.toIntOrNull() ?: 1).coerceIn(1, pages)
            val lines = everyone.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).map { (profile, nickname) ->
                val online = if (server.playerList.getPlayer(profile.id) != null) "●" else "○"
                "$online ${profile.name} | ${nickname ?: "-"} | ${profile.id}"
            }
            val header = "플레이어 ${everyone.size}명 (${page}/${pages}쪽, ● 접속 중)\n아이디 | 닉네임 | UUID"
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
        override val description = "플레이어를 밴한다 (접속 중이면 바로 내보냄)"
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("reason", "밴 사유", required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val bans = server.playerList.bans
            if (bans.isBanned(profile)) return DiscordRest.message("${profile.name}은(는) 이미 밴되어 있어요.")
            val reason = options["reason"]?.trim()?.ifEmpty { null }
            bans.add(UserBanListEntry(profile, null, "Discord: ${caller.userName}", null, reason))
            server.playerList.getPlayer(profile.id)?.connection?.disconnect(Component.translatable("multiplayer.disconnect.banned"))
            return DiscordRest.message("${profile.name}을(를) 밴했어요." + (reason?.let { " 사유: $it" } ?: ""))
        }
    }

    private val unban = object : DiscordAdminCommand {
        override val name = "unban"
        override val description = "플레이어의 밴을 푼다"
        override val options = JsonArray().apply { add(playerOption()) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val bans = server.playerList.bans
            if (!bans.isBanned(profile)) return DiscordRest.message("${profile.name}은(는) 밴되어 있지 않아요.")
            bans.remove(profile)
            return DiscordRest.message("${profile.name}의 밴을 풀었어요.")
        }
    }

    private val give = object : DiscordAdminCommand {
        override val name = "give"
        override val description = "플레이어에게 아이템을 준다 (접속하지 않았으면 다음 접속 때)"
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("item", "아이템 ID, /give처럼 (예: cobblemon:rare_candy)"))
            add(DiscordCommands.integerOption("count", "개수 (기본 1)", 1, required = false))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val count = options["count"]?.toIntOrNull() ?: 1
            if (count !in 1..MAX_ITEMS) return DiscordRest.message("개수는 1에서 $MAX_ITEMS 사이로 적어 주세요.")
            val item = options["item"].orEmpty().trim()
            val one = try {
                ItemArgument(CommandBuildContext.simple(server.registryAccess(), server.worldData.enabledFeatures()))
                    .parse(StringReader(item)).createItemStack(1, false)
            } catch (failure: Exception) {
                return DiscordRest.message("'$item' 아이템을 알아보지 못했어요. ${failure.message.orEmpty()}")
            }
            val stacks = generateSequence(count) { left -> (left - one.maxStackSize).takeIf { it > 0 } }
                .map { left -> one.copyWithCount(minOf(left, one.maxStackSize)) }.toList()
            val itemName = KoreanText.render(one.hoverName)
            val online = server.playerList.getPlayer(profile.id)
            if (online != null) {
                PendingItems.hand(online, stacks)
                return DiscordRest.message("${profile.name}에게 $itemName ${count}개를 줬어요.")
            }
            PendingItems.get(server).add(profile.id, stacks)
            return DiscordRest.message("${profile.name}은(는) 접속 중이 아니라서, 다음에 접속할 때 $itemName ${count}개를 받아요.")
        }
    }

    private val spawn = object : DiscordAdminCommand {
        override val name = "spawn"
        override val description = "접속 중인 플레이어 앞에 그 플레이어의 포켓몬을 스폰한다"
        override val options = JsonArray().apply {
            add(playerOption())
            add(DiscordCommands.stringOption("pokemon", "/spawnpokemonfor처럼 (예: mewtwo level=70 shiny)"))
        }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val asked = options["player"].orEmpty()
            val profile = profile(server, asked) ?: return unknown(asked)
            val player = server.playerList.getPlayer(profile.id)
                ?: return DiscordRest.message("${profile.name}은(는) 접속 중이 아니에요. 포켓몬은 접속 중인 플레이어 앞에만 스폰할 수 있어요.")
            val text = options["pokemon"].orEmpty().trim()
            val properties = PokemonProperties.parse(text)
            if (properties.species == null) return DiscordRest.message("'$text'에서 포켓몬을 알아보지 못했어요.")
            val spawned = SpawnForCommand.spawn(player, properties) ?: return DiscordRest.message("포켓몬을 스폰하지 못했어요.")
            val lines = listOf("${profile.name} 앞에 ${KoreanText.render(spawned.entity.pokemon.species.translatedName)}" +
                " (Lv. ${spawned.entity.pokemon.level})을(를) 스폰했어요.") + spawned.warnings.map { "⚠ " + KoreanText.render(it) }
            return DiscordRest.message(lines.joinToString("\n"))
        }
    }

    private val console = object : DiscordAdminCommand {
        override val name = "console"
        override val description = "서버 콘솔 명령을 실행한다 (앞의 / 없이)"
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("command", "예: weather clear")) }

        override fun run(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val command = options["command"].orEmpty().trim().removePrefix("/")
            if (command.isEmpty()) return DiscordRest.message("명령이 비어 있어요.")
            val output = mutableListOf<String>()
            val capture = object : CommandSource {
                override fun sendSystemMessage(message: Component) { output += KoreanText.render(message) }
                override fun acceptsSuccess() = true
                override fun acceptsFailure() = true
                override fun shouldInformAdmins() = true
            }
            server.commands.performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command)
            val shown = output.joinToString("\n").ifBlank { "(출력 없음)" }.replace("```", "'''")
            return DiscordRest.message("`/$command`\n```\n${shown.take(CODE_BLOCK_LIMIT)}\n```")
        }
    }
}
