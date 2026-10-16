package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.Cobblemon
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * `/release <slot>` asks, and a confirm within two minutes releases. A client that can show it gets the question as a
 * popup ([ReleaseAskPayload]); any other gets the chat line with a clickable [Yes]. Both confirm through the same
 * `/release confirm <token>`. The last Pokemon always stays.
 */
object PartyRelease {
    private const val CONFIRMATION_MILLIS = 2 * 60 * 1000L
    private const val KEY = "message.${JbroPolicy.MOD_ID}.release."
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<UUID, Pending>()

    private data class Pending(val token: String, val slot: Int, val pokemon: UUID, val expiresAt: Long)

    fun register() {
        PayloadTypeRegistry.playS2C().register(ReleaseAskPayload.TYPE, ReleaseAskPayload.CODEC)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("release")
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                    .executes { request(it.source.playerOrException, IntegerArgumentType.getInteger(it, "slot")) })
                .then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word())
                    .executes { confirm(it.source.playerOrException, StringArgumentType.getString(it, "token")) })))
        }
    }

    private fun request(player: ServerPlayer, slot: Int): Int {
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(slot - 1) ?: return fail(player, Component.translatable(KEY + "empty", slot))
        if (party.occupied() <= 1) return fail(player, Component.translatable(KEY + "last"))
        val token = java.lang.Long.toUnsignedString(random.nextLong(), 36)
        pending[player.uuid] = Pending(token, slot - 1, pokemon.uuid, System.currentTimeMillis() + CONFIRMATION_MILLIS)
        if (ServerPlayNetworking.canSend(player, ReleaseAskPayload.TYPE)) {
            ServerPlayNetworking.send(player, ReleaseAskPayload(token, slot, pokemon.getDisplayName(false), pokemon.level))
            return 1
        }
        val yes = Component.translatable(KEY + "yes").withStyle { style ->
            style.withColor(ChatFormatting.GREEN).withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/release confirm $token"))
        }
        player.sendSystemMessage(Component.translatable(KEY + "ask", slot, pokemon.getDisplayName(false)).append(" ").append(yes))
        return 1
    }

    private fun confirm(player: ServerPlayer, token: String): Int {
        val request = pending.remove(player.uuid)
        if (request == null || request.token != token || request.expiresAt < System.currentTimeMillis()) {
            return fail(player, Component.translatable(KEY + "expired"))
        }
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(request.slot)
        if (pokemon == null || pokemon.uuid != request.pokemon) return fail(player, Component.translatable(KEY + "changed"))
        if (party.occupied() <= 1) return fail(player, Component.translatable(KEY + "last"))
        val name = pokemon.getDisplayName(false)
        if (!party.remove(pokemon)) return fail(player, Component.translatable(KEY + "failed"))
        player.sendSystemMessage(Component.translatable(KEY + "done", name))
        return 1
    }

    private fun fail(player: ServerPlayer, message: Component): Int {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED))
        return 0
    }
}

/** Asks the client to confirm releasing party slot [slot], [name] at [level]; the confirm runs `/release confirm [token]`. */
data class ReleaseAskPayload(val token: String, val slot: Int, val name: Component, val level: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ReleaseAskPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ReleaseAskPayload>(JbroPolicy.id("release_ask"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ReleaseAskPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), ReleaseAskPayload::token,
            ByteBufCodecs.VAR_INT, ReleaseAskPayload::slot,
            ComponentSerialization.STREAM_CODEC, ReleaseAskPayload::name,
            ByteBufCodecs.VAR_INT, ReleaseAskPayload::level,
            ::ReleaseAskPayload,
        )
    }
}
