package jbro.cobblemon.toitem.network

import jbro.cobblemon.toitem.CobblemonToItem
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

data class RestoreInventoryPayload(val menuSlot: Int, val pokemonId: UUID) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<RestoreInventoryPayload>(CobblemonToItem.id("restore_inventory"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RestoreInventoryPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeVarInt(payload.menuSlot); buffer.writeUUID(payload.pokemonId) },
            { buffer -> RestoreInventoryPayload(buffer.readVarInt(), buffer.readUUID()) },
        )
    }
}
