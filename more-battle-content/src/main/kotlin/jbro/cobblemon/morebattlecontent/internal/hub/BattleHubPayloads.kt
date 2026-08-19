package jbro.cobblemon.morebattlecontent.internal.hub

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal enum class BattleHubContent {
    BATTLE_TOWER,
    BATTLE_FACTORY,
    PVP,
    BOSS_RAID,
    SHOP,
}

internal data object BattleHubStatePayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleHubStatePayload> = TYPE

    val TYPE = CustomPacketPayload.Type<BattleHubStatePayload>(id("battle_hub_state"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubStatePayload> = StreamCodec.of(
        { _, _ -> Unit },
        { BattleHubStatePayload },
    )
}

internal data class BattleHubHeaderStatePayload(val bpBalance: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleHubHeaderStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleHubHeaderStatePayload>(id("battle_hub_header_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubHeaderStatePayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeVarLong(payload.bpBalance) },
            { buffer -> BattleHubHeaderStatePayload(buffer.readVarLong()) },
        )
    }
}

internal data class BattleHubOpenContentPayload(val content: BattleHubContent) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleHubOpenContentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleHubOpenContentPayload>(id("battle_hub_open_content"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubOpenContentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeVarInt(payload.content.ordinal) },
            { buffer ->
                val ordinal = buffer.readVarInt()
                require(ordinal in BattleHubContent.entries.indices) { "Invalid battle hub content: $ordinal" }
                BattleHubOpenContentPayload(BattleHubContent.entries[ordinal])
            },
        )
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)
