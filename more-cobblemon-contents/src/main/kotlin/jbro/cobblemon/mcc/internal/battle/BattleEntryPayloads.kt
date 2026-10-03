package jbro.cobblemon.mcc.internal.battle

import java.util.Optional
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Tells the client to play the battle entry transition before the server starts a battle: against [species] in the
 * wild, or a trainer when [trainer] is set. The client picks the legendary transition and its colour from the species.
 */
internal data class StartBattleEntryPayload(
    val species: ResourceLocation?,
    val trainer: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StartBattleEntryPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<StartBattleEntryPayload>(entryId("battle_entry_start"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, StartBattleEntryPayload> = StreamCodec.of(
            { buffer, payload ->
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buffer, Optional.ofNullable(payload.species))
                buffer.writeBoolean(payload.trainer)
            },
            { buffer ->
                StartBattleEntryPayload(
                    species = ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buffer).orElse(null),
                    trainer = buffer.readBoolean(),
                )
            },
        )
    }
}

/** The battle the transition was played for will not start; the client clears the screen. */
internal object CancelBattleEntryPayload : CustomPacketPayload {
    val TYPE = CustomPacketPayload.Type<CancelBattleEntryPayload>(entryId("battle_entry_cancel"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, CancelBattleEntryPayload> = StreamCodec.unit(CancelBattleEntryPayload)

    override fun type(): CustomPacketPayload.Type<CancelBattleEntryPayload> = TYPE
}

internal object BattleEntryNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(StartBattleEntryPayload.TYPE, StartBattleEntryPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CancelBattleEntryPayload.TYPE, CancelBattleEntryPayload.CODEC)
    }
}

private fun entryId(path: String) = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)
