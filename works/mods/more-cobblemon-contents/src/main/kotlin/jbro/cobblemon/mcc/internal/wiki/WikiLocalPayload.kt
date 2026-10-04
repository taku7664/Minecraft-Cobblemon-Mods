package jbro.cobblemon.mcc.internal.wiki

import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Sent by a client that serves its own copy of the wiki on localhost, naming the port, so `/wiki` links open it. */
internal data class WikiLocalPayload(val port: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WikiLocalPayload> = TYPE

    companion object {
        /** The ports a client tries, in order; the first one free wins, so a browser keeps its saved token. */
        val PORTS = 18100..18109

        val TYPE = CustomPacketPayload.Type<WikiLocalPayload>(ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "wiki_local"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WikiLocalPayload> =
            ByteBufCodecs.VAR_INT.map(::WikiLocalPayload, WikiLocalPayload::port).cast()
    }
}
