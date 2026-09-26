package jbro.cobblemon.morebattlecontent.internal.hub

import jbro.cobblemon.morebattlecontent.api.access.ContentAccessDecision
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Separate optional channel keeps the existing hub/header protocol compatible. */
internal data class BattleHubAccessPayload(val denied: Map<BattleHubContent, ContentAccessDecision.Denied>) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<BattleHubAccessPayload>(ResourceLocation.parse("cobblemon_more_battle_content:hub_access_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubAccessPayload> = StreamCodec.of(
            { b, p ->
                b.writeVarInt(p.denied.size)
                p.denied.forEach { (content, reason) ->
                    b.writeEnum(content); b.writeUtf(reason.reasonKey, 256); b.writeUtf(reason.code, 128)
                    b.writeVarInt(reason.arguments.size); reason.arguments.forEach { b.writeUtf(it, 256) }
                }
            },
            { b ->
                val size = b.readVarInt().also { require(it in 0..BattleHubContent.entries.size) }
                val result = linkedMapOf<BattleHubContent, ContentAccessDecision.Denied>()
                repeat(size) {
                    val content = b.readEnum(BattleHubContent::class.java)
                    val key = b.readUtf(256); val code = b.readUtf(128)
                    val count = b.readVarInt().also { require(it in 0..8) }
                    require(result.put(content, ContentAccessDecision.Denied(key, code, List(count) { b.readUtf(256) })) == null)
                }
                BattleHubAccessPayload(result)
            })
    }
}
