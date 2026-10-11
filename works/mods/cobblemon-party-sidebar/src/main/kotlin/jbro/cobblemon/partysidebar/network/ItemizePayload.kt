package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/**
 * Turns the Pokemon in party [slot] into a PokemonToItem item. [pokemonId] guards against a party that changed.
 * [targetSlot] is the inventory menu slot it was dropped on, or [ANY_SLOT] to put the item wherever it fits.
 */
data class ItemizePayload(val slot: Int, val pokemonId: UUID, val targetSlot: Int = ANY_SLOT) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val ANY_SLOT = -1
        val TYPE = CustomPacketPayload.Type<ItemizePayload>(CobblemonPartySidebar.id("itemize"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ItemizePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.slot)
                buffer.writeUUID(payload.pokemonId)
                buffer.writeVarInt(payload.targetSlot + 1)
            },
            { buffer -> ItemizePayload(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt() - 1) },
        )
    }
}

/** Puts the PokemonToItem item on the cursor back into party [slot], which must be empty. */
data class RestorePayload(val slot: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<RestorePayload>(CobblemonPartySidebar.id("restore"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RestorePayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeVarInt(payload.slot) },
            { buffer -> RestorePayload(buffer.readVarInt()) },
        )
    }
}
