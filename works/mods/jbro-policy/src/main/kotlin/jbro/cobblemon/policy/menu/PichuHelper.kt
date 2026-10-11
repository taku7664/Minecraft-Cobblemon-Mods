package jbro.cobblemon.policy.menu

import io.netty.buffer.ByteBuf
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.support.KoreanText
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * `/피츄` on the server: asks the player's client to open Pichu's helper screen. Typed in chat, the client's own
 * `/피츄` answers first; this one is what an NPC talk runs, since a dialogue's commands run on the server.
 */
object PichuHelper {
    fun register() {
        PayloadTypeRegistry.playS2C().register(OpenPichuPayload.TYPE, OpenPichuPayload.CODEC)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            for (name in listOf("피츄", "pichu")) dispatcher.register(Commands.literal(name).executes { context ->
                val player = context.source.playerOrException
                if (!ServerPlayNetworking.canSend(player, OpenPichuPayload.TYPE)) {
                    player.sendSystemMessage(KoreanText.message("message.${JbroPolicy.MOD_ID}.pichu.unavailable").withStyle(ChatFormatting.RED))
                    return@executes 0
                }
                ServerPlayNetworking.send(player, OpenPichuPayload)
                1
            })
        }
    }
}

/** Opens Pichu's helper screen on the client. */
data object OpenPichuPayload : CustomPacketPayload {
    val TYPE = CustomPacketPayload.Type<OpenPichuPayload>(JbroPolicy.id("open_pichu"))
    val CODEC: StreamCodec<ByteBuf, OpenPichuPayload> = StreamCodec.unit(OpenPichuPayload)

    override fun type(): CustomPacketPayload.Type<OpenPichuPayload> = TYPE
}
