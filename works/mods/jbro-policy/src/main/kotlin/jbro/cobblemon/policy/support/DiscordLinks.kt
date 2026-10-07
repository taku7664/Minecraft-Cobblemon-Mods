package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/**
 * Links each player's Minecraft account to one Discord account. `/디코인증` (`/discordverify`) in game gives a
 * six-digit code; `/verify <code>` on Discord links the two, gives the member the verified role and, unless turned off,
 * sets their server nickname to their Minecraft nickname. Relinking either account replaces its old link.
 *
 * The links are kept with the world and mirrored here, so threads off the server thread (inquiries) can read them.
 */
internal object DiscordLinks {
    private const val CODE_MINUTES = 10L
    private const val NICKNAME_LIMIT = 32
    private const val KEY = "message.${JbroPolicy.MOD_ID}.discord_link."

    private class Pending(val player: UUID, val accountName: String, val nickname: String, val expiresAt: Long)

    private val random = SecureRandom()
    private val codes = ConcurrentHashMap<String, Pending>()
    private val byPlayer = ConcurrentHashMap<UUID, String>()

    /** The Discord account [player] linked, if any. Safe from any thread. */
    fun discordOf(player: UUID): String? = byPlayer[player]

    /** Every link, Minecraft UUID to Discord user ID. */
    fun all(): Map<UUID, String> = HashMap(byPlayer)

    /** Turns linking on: the in-game command and Discord's `/verify`. */
    fun registerDiscord(settings: DiscordSettings) {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            byPlayer.clear()
            byPlayer.putAll(Store.get(server).links)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            for (name in listOf("디코인증", "discordverify")) {
                dispatcher.register(Commands.literal(name).executes { context ->
                    val player = context.source.playerOrException
                    val code = issue(player, System.currentTimeMillis())
                    player.sendSystemMessage(KoreanText.message(KEY + "code",
                        Component.literal(code).withStyle { style ->
                            style.withColor(ChatFormatting.AQUA).withBold(true)
                                .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, "/verify $code"))
                                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, KoreanText.message(KEY + "copy")))
                        }, CODE_MINUTES).withStyle(ChatFormatting.GREEN))
                    1
                }.then(Commands.literal("linked")
                    // 1 when the player has linked Discord, 0 when not; an NPC dialogue asks it (`cmd:디코인증 linked`).
                    .requires { it.hasPermission(2) }
                    .executes { context -> if (byPlayer.containsKey(context.source.playerOrException.uuid)) 1 else 0 }))
            }
        }
        DiscordCommands.add(verify(settings))
    }

    /** A fresh code for [player]; their older code stops working. */
    private fun issue(player: ServerPlayer, now: Long): String {
        codes.entries.removeIf { it.value.player == player.uuid || it.value.expiresAt <= now }
        val nickname = KoreanText.render(player.displayName ?: player.name).ifBlank { player.gameProfile.name }
        while (true) {
            val code = "%06d".format(random.nextInt(1_000_000))
            val pending = Pending(player.uuid, player.gameProfile.name, nickname, now + CODE_MINUTES * 60_000)
            if (codes.putIfAbsent(code, pending) == null) return code
        }
    }

    private fun verify(settings: DiscordSettings) = object : DiscordCommand {
        override val name = "verify"
        override val description = "마크에서 /디코인증으로 받은 코드로 마인크래프트 계정을 연결한다"
        override val options = JsonArray().apply { add(DiscordCommands.stringOption("code", "마크에서 받은 6자리 코드")) }
        override val ephemeral = true
        override val anyChannel = true

        override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject = error("/verify needs its caller")

        override fun reply(server: MinecraftServer, options: Map<String, String>, caller: DiscordCaller): JsonObject {
            val code = options["code"].orEmpty().trim()
            val pending = codes[code]?.takeIf { it.expiresAt > System.currentTimeMillis() }
                ?: return DiscordRest.message("코드가 맞지 않거나 시간이 지났어요. 마크에서 /디코인증을 다시 입력해 주세요.")
            if (caller.guildId.isBlank()) return DiscordRest.message("디스코드 서버 채널에서 입력해 주세요.")
            codes.remove(code)
            val previous = Store.get(server).link(pending.player, caller.userId)
            byPlayer.entries.removeIf { it.value == caller.userId }
            byPlayer[pending.player] = caller.userId
            JbroPolicy.LOGGER.info("Linked Discord {} to {} ({})", caller, pending.accountName, pending.player)
            server.playerList.getPlayer(pending.player)?.sendSystemMessage(
                KoreanText.message(KEY + "done", caller.userName).withStyle(ChatFormatting.GREEN))
            DiscordBot.later { rest -> grant(rest, settings, caller, pending.nickname) }
            if (previous != null && previous != caller.userId) DiscordRankRoles.clear(previous)
            DiscordRankRoles.refresh(server, pending.player)
            return DiscordRest.message("인증됐어요! 마인크래프트 계정 **${pending.accountName}**과 연결했어요.")
        }
    }

    /** The verified role, then the nickname; Discord refuses the owner's nickname, which only logs. */
    private fun grant(rest: DiscordRest, settings: DiscordSettings, caller: DiscordCaller, nickname: String) {
        val role = rest.request("PUT", "/guilds/${caller.guildId}/members/${caller.userId}/roles/${settings.verifiedRoleId}")
        if (!role.ok) JbroPolicy.LOGGER.warn("Could not give {} the verified role ({}): {}", caller, role.status, role.body.take(200))
        if (!settings.syncNickname) return
        val nick = rest.request("PATCH", "/guilds/${caller.guildId}/members/${caller.userId}",
            JsonObject().apply { addProperty("nick", nickname.take(NICKNAME_LIMIT)) })
        if (!nick.ok) JbroPolicy.LOGGER.info("Could not set {}'s nickname ({}): {}", caller, nick.status, nick.body.take(200))
    }

    /** The links, kept with the world: Minecraft UUID to Discord user ID. */
    private class Store(val links: MutableMap<UUID, String>) : SavedData() {
        /** Links [player] to [discord]; answers the Discord account [player] had before. */
        fun link(player: UUID, discord: String): String? {
            links.entries.removeIf { it.value == discord && it.key != player }
            val previous = links.put(player, discord)
            setDirty()
            return previous
        }

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
            put("links", CompoundTag().apply { links.forEach { (player, discord) -> putString(player.toString(), discord) } })
        }

        companion object {
            private val factory = Factory({ Store(mutableMapOf()) }, { tag, _ ->
                val links = tag.getCompound("links")
                Store(links.allKeys.mapNotNull { key ->
                    runCatching { UUID.fromString(key) }.getOrNull()?.let { it to links.getString(key) }
                }.toMap(mutableMapOf()))
            }, DataFixTypes.LEVEL)

            fun get(server: MinecraftServer): Store = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_discord_links")
        }
    }
}
