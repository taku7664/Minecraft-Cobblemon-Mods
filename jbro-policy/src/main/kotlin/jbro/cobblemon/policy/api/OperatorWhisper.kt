package jbro.cobblemon.policy.api

import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/**
 * A private message from the operators, in gray: `[운영자로부터 메세지가 왔어요]`, then the quoted text it answers,
 * underlined, then the message. A player who is offline gets it the next time they join; other mods call [send] too.
 */
object OperatorWhisper {
    private const val HEADER_KEY = "message.${JbroPolicy.MOD_ID}.operator_whisper"

    /** Whispers [message] to [playerId], quoting [quote] (the inquiry it answers, say) above it when given. */
    @JvmStatic
    @JvmOverloads
    fun send(server: MinecraftServer, playerId: UUID, message: String, quote: String? = null) {
        val online = server.playerList.getPlayer(playerId)
        if (online != null) online.sendSystemMessage(component(message, quote))
        else Waiting.get(server).add(playerId, Whisper(message, quote))
    }

    /** The whole whisper as one gray message. */
    @JvmStatic
    fun component(message: String, quote: String?): Component = Component.empty().withStyle(ChatFormatting.GRAY).apply {
        append(Component.translatable(HEADER_KEY))
        if (!quote.isNullOrBlank()) append("\n").append(Component.literal("\"$quote\"").withStyle(ChatFormatting.UNDERLINE))
        append("\n").append(message)
    }

    internal fun register() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            for (whisper in Waiting.get(server).take(handler.player.uuid)) {
                handler.player.sendSystemMessage(component(whisper.message, whisper.quote))
            }
        }
    }

    private data class Whisper(val message: String, val quote: String?)

    /** Whispers kept for players who were offline, oldest first. */
    private class Waiting(private val waiting: MutableMap<UUID, MutableList<Whisper>>) : SavedData() {
        fun add(player: UUID, whisper: Whisper) {
            waiting.getOrPut(player) { mutableListOf() }.add(whisper)
            setDirty()
        }

        fun take(player: UUID): List<Whisper> = waiting.remove(player).orEmpty().also { if (it.isNotEmpty()) setDirty() }

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
            put("players", CompoundTag().apply {
                for ((player, whispers) in waiting) if (whispers.isNotEmpty()) {
                    put(player.toString(), ListTag().apply {
                        for (whisper in whispers) add(CompoundTag().apply {
                            putString("message", whisper.message)
                            whisper.quote?.let { putString("quote", it) }
                        })
                    })
                }
            })
        }

        companion object {
            private val factory = Factory({ Waiting(mutableMapOf()) }, { tag, _ ->
                val players = tag.getCompound("players")
                Waiting(players.allKeys.mapNotNull { key ->
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
                    uuid to players.getList(key, Tag.TAG_COMPOUND.toInt()).map { entry ->
                        val whisper = entry as CompoundTag
                        Whisper(whisper.getString("message"), whisper.getString("quote").ifEmpty { null })
                    }.toMutableList()
                }.toMap(mutableMapOf()))
            }, DataFixTypes.LEVEL)

            fun get(server: MinecraftServer): Waiting = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_operator_whispers")
        }
    }
}
