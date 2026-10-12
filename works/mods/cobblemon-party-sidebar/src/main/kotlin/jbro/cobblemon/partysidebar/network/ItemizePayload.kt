package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/**
 * Turns the Pokemon in party [slot] into a Cobblemon To Item item. [pokemonId] guards against a party that changed.
 * Ordinary clicks pick up onto the cursor; shift-clicks move directly to the inventory.
 */
data class ItemizePayload(val slot: Int, val pokemonId: UUID, val toCursor: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ItemizePayload>(CobblemonPartySidebar.id("itemize"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ItemizePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.slot)
                buffer.writeUUID(payload.pokemonId)
                buffer.writeBoolean(payload.toCursor)
            },
            { buffer -> ItemizePayload(buffer.readVarInt(), buffer.readUUID(), buffer.readBoolean()) },
        )
    }
}

/** Places the cursor Pokemon in a party slot, exchanging any expected occupant onto the cursor. */
data class RestorePayload(val slot: Int, val pokemonId: UUID, val targetId: UUID?) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<RestorePayload>(CobblemonPartySidebar.id("restore"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RestorePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.slot); buffer.writeUUID(payload.pokemonId)
                buffer.writeBoolean(payload.targetId != null)
                payload.targetId?.let { buffer.writeUUID(it) }
            },
            { buffer -> RestorePayload(buffer.readVarInt(), buffer.readUUID(), if (buffer.readBoolean()) buffer.readUUID() else null) },
        )
    }
}
