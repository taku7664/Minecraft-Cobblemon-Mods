package jbro.cobblemon.policy.pack

import io.netty.buffer.ByteBuf
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.support.KoreanText
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.network.ConfigurationTask
import java.util.function.Consumer

/**
 * Turns away a client whose modpack is not the server's release line. While the connection is configuring, the
 * server asks for the client's pack version and compares the first two numbers only, so 1.2.0 and 1.2.3 meet but
 * 1.1.x does not. A client without this check (an older pack, or no jbro-policy) cannot answer and is turned away too.
 */
object PackCheck {
    /** The modpack release this jar ships in. Bump it with every release. */
    const val PACK_VERSION = "1.3.0"

    /** Download hint for the refusal, the Discord invite; blank leaves it out. */
    var inviteUrl: String = ""

    fun register() {
        PayloadTypeRegistry.configurationS2C().register(PackCheckRequest.TYPE, PackCheckRequest.CODEC)
        PayloadTypeRegistry.configurationC2S().register(PackCheckReply.TYPE, PackCheckReply.CODEC)
        ServerConfigurationConnectionEvents.CONFIGURE.register { handler, _ ->
            if (ServerConfigurationNetworking.canSend(handler, PackCheckRequest.TYPE)) handler.addTask(Task)
            else handler.disconnect(refusal(null))
        }
        ServerConfigurationNetworking.registerGlobalReceiver(PackCheckReply.TYPE) { payload, context ->
            if (sameLine(payload.version, PACK_VERSION)) context.networkHandler().completeTask(Task.TYPE)
            else {
                JbroPolicy.LOGGER.info("Refused a client on modpack {} (server {})", payload.version, PACK_VERSION)
                context.networkHandler().disconnect(refusal(payload.version))
            }
        }
    }

    /** True when both versions share their first two numbers; anything unreadable never matches. */
    fun sameLine(client: String, server: String): Boolean {
        val a = line(client) ?: return false
        return a == line(server)
    }

    private fun line(version: String): Pair<Int, Int>? {
        val parts = version.trim().split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        return major to minor
    }

    private fun refusal(clientVersion: String?): Component {
        val text = if (clientVersion == null) KoreanText.message("message.${JbroPolicy.MOD_ID}.pack.outdated", PACK_VERSION)
        else KoreanText.message("message.${JbroPolicy.MOD_ID}.pack.mismatch", clientVersion, PACK_VERSION)
        if (inviteUrl.isBlank()) return text
        return text.append("\n\n").append(KoreanText.message("message.${JbroPolicy.MOD_ID}.pack.download", inviteUrl))
    }

    private object Task : ConfigurationTask {
        val TYPE = ConfigurationTask.Type("${JbroPolicy.MOD_ID}:pack_check")
        override fun start(sender: Consumer<Packet<*>>) = sender.accept(ServerConfigurationNetworking.createS2CPacket(PackCheckRequest))
        override fun type(): ConfigurationTask.Type = TYPE
    }
}

/** Server to client: tell me your modpack version. */
data object PackCheckRequest : CustomPacketPayload {
    val TYPE = CustomPacketPayload.Type<PackCheckRequest>(JbroPolicy.id("pack_check_request"))
    val CODEC: StreamCodec<ByteBuf, PackCheckRequest> = StreamCodec.unit(PackCheckRequest)

    override fun type(): CustomPacketPayload.Type<PackCheckRequest> = TYPE
}

/** Client to server: the modpack version this client's jbro-policy ships in. */
data class PackCheckReply(val version: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PackCheckReply> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PackCheckReply>(JbroPolicy.id("pack_check_reply"))
        val CODEC: StreamCodec<ByteBuf, PackCheckReply> = ByteBufCodecs.STRING_UTF8.map(::PackCheckReply, PackCheckReply::version)
    }
}
