package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/** Turns the Pokemon in party [slot] into a PokemonToItem item. [pokemonId] guards against a party that changed. */
data class ItemizePayload(val slot: Int, val pokemonId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ItemizePayload>(CobblemonPartySidebar.id("itemize"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ItemizePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.slot)
                buffer.writeUUID(payload.pokemonId)
            },
            { buffer -> ItemizePayload(buffer.readVarInt(), buffer.readUUID()) },
        )
    }
}
