package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Hub entries the player may not open, keyed by hub content ID. */
internal data class BattleHubAccessPayload(val denied: Map<String, ContentAccessDecision.Denied>) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        const val MAX_ENTRIES = 64

        val TYPE = CustomPacketPayload.Type<BattleHubAccessPayload>(ResourceLocation.parse("more_cobblemon_contents:hub_access_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubAccessPayload> = StreamCodec.of(
            { b, p ->
                require(p.denied.size <= MAX_ENTRIES) { "Too many hub access entries" }
                b.writeVarInt(p.denied.size)
                p.denied.forEach { (contentId, reason) ->
                    b.writeUtf(contentId, BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH)
                    b.writeUtf(reason.reasonKey, 256); b.writeUtf(reason.code, 128)
                    b.writeVarInt(reason.arguments.size); reason.arguments.forEach { b.writeUtf(it, 256) }
                }
            },
            { b ->
                val size = b.readVarInt().also { require(it in 0..MAX_ENTRIES) }
                val result = linkedMapOf<String, ContentAccessDecision.Denied>()
                repeat(size) {
                    val contentId = b.readUtf(BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH)
                    val key = b.readUtf(256); val code = b.readUtf(128)
                    val count = b.readVarInt().also { require(it in 0..8) }
                    require(result.put(contentId, ContentAccessDecision.Denied(key, code, List(count) { b.readUtf(256) })) == null)
                }
                BattleHubAccessPayload(result)
            })
    }
}
