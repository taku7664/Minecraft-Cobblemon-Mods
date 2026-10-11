package jbro.cobblemon.policy.menu

import io.netty.buffer.ByteBuf
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.support.KoreanText
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * The pause menu's wiki and Discord buttons. The client knows neither address: the wiki link is each player's own and
 * the invite lives in the server's Discord config, so a button asks the server, which answers with the page to open.
 */
object MenuLinks {
    private const val KEY = "message.${JbroPolicy.MOD_ID}.menu_link."

    /** The server's Discord invite; blank when none is set. */
    @Volatile
    var inviteUrl: String = ""

    fun register() {
        PayloadTypeRegistry.playC2S().register(RequestLinkPayload.TYPE, RequestLinkPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenLinkPayload.TYPE, OpenLinkPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(RequestLinkPayload.TYPE) { payload, context ->
            val player = context.player()
            val url = when (payload.link) {
                RequestLinkPayload.WIKI -> wikiLink(player)
                RequestLinkPayload.DISCORD -> inviteUrl.ifBlank { null }
                else -> return@registerGlobalReceiver
            }
            if (url != null) ServerPlayNetworking.send(player, OpenLinkPayload(url))
            else player.sendSystemMessage(KoreanText.message(KEY + payload.link + "_off").withStyle(ChatFormatting.RED))
        }
    }

    /** More Cobblemon Contents serves the wiki; without it there is none. */
    private fun wikiLink(player: ServerPlayer): String? =
        if (FabricLoader.getInstance().isModLoaded("more_cobblemon_contents")) jbro.cobblemon.mcc.api.wiki.WikiApi.linkFor(player.uuid)
        else null
}

/** Asks the server for the page behind a menu button: [WIKI] or [DISCORD]. */
data class RequestLinkPayload(val link: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RequestLinkPayload> = TYPE

    companion object {
        const val WIKI = "wiki"
        const val DISCORD = "discord"
        val TYPE = CustomPacketPayload.Type<RequestLinkPayload>(JbroPolicy.id("request_link"))
        val CODEC: StreamCodec<ByteBuf, RequestLinkPayload> =
            ByteBufCodecs.stringUtf8(32).map(::RequestLinkPayload, RequestLinkPayload::link)
    }
}

/** The page the client should open in the browser. */
data class OpenLinkPayload(val url: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenLinkPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenLinkPayload>(JbroPolicy.id("open_link"))
        val CODEC: StreamCodec<ByteBuf, OpenLinkPayload> =
            ByteBufCodecs.stringUtf8(2048).map(::OpenLinkPayload, OpenLinkPayload::url)
    }
}
