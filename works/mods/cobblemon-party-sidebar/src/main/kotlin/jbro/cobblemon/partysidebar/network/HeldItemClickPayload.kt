package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/**
 * A click on the held-item slot of party [slot]. [pokemonId] guards against a party that changed in between.
 * A plain click swaps the slot with the cursor item; [quickMove] (shift-click) sends the held item to the inventory.
 */
data class HeldItemClickPayload(val slot: Int, val pokemonId: UUID, val quickMove: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HeldItemClickPayload>(CobblemonPartySidebar.id("held_item_click"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HeldItemClickPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.slot)
                buffer.writeUUID(payload.pokemonId)
                buffer.writeBoolean(payload.quickMove)
            },
            { buffer -> HeldItemClickPayload(buffer.readVarInt(), buffer.readUUID(), buffer.readBoolean()) },
        )
    }
}
