package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Opens the hub showing only [tabs], on [initialTab]. */
internal data class BattleHubStatePayload(val tabs: List<String>, val initialTab: String) : CustomPacketPayload {
    init {
        require(tabs.isNotEmpty() && tabs.size <= MAX_TABS && tabs.distinct().size == tabs.size) { "Invalid hub tabs: $tabs" }
        require(tabs.all(ManagedBattleContentIds::isValid) && initialTab in tabs) { "Invalid hub tabs: $tabs from $initialTab" }
    }

    override fun type(): CustomPacketPayload.Type<BattleHubStatePayload> = TYPE

    companion object {
        const val MAX_TABS = 32

        val TYPE = CustomPacketPayload.Type<BattleHubStatePayload>(id("battle_hub_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.tabs.size)
                payload.tabs.forEach { buffer.writeUtf(it, BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH) }
                buffer.writeUtf(payload.initialTab, BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH)
            },
            { buffer ->
                val count = buffer.readVarInt()
                require(count in 1..MAX_TABS) { "Invalid hub tab count: $count" }
                BattleHubStatePayload(
                    List(count) { buffer.readUtf(BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH) },
                    buffer.readUtf(BattleHubOpenContentPayload.MAX_CONTENT_ID_LENGTH),
                )
            },
        )
    }
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

internal data class BattleHubOpenContentPayload(val contentId: String) : CustomPacketPayload {
    init {
        require(ManagedBattleContentIds.isValid(contentId)) { "Invalid battle hub content: $contentId" }
    }

    override fun type(): CustomPacketPayload.Type<BattleHubOpenContentPayload> = TYPE

    companion object {
        const val MAX_CONTENT_ID_LENGTH = 128

        val TYPE = CustomPacketPayload.Type<BattleHubOpenContentPayload>(id("battle_hub_open_content"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleHubOpenContentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.contentId, MAX_CONTENT_ID_LENGTH) },
            { buffer -> BattleHubOpenContentPayload(buffer.readUtf(MAX_CONTENT_ID_LENGTH)) },
        )
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)
