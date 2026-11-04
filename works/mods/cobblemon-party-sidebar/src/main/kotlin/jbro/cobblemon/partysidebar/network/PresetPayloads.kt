package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.ItemStack

/** A sidebar button: switch to preset [index], add a preset, delete the current one, or open the item storage. */
data class PresetActionPayload(val action: Action, val index: Int) : CustomPacketPayload {
    enum class Action { SELECT, ADD, DELETE, OPEN_STORAGE }

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PresetActionPayload>(CobblemonPartySidebar.id("preset_action"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PresetActionPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeEnum(payload.action)
                buffer.writeVarInt(payload.index)
            },
            { buffer -> PresetActionPayload(buffer.readEnum(Action::class.java), buffer.readVarInt()) },
        )
    }
}

/**
 * What the sidebar shows of the presets: the current one of [count], and for each party row the storage item its
 * preset lends but the Pokemon is not holding ([missing], EMPTY when nothing is missing).
 */
data class PresetStatePayload(val active: Int, val count: Int, val missing: List<ItemStack>) : CustomPacketPayload {
    override fun type() = TYPE

    override fun equals(other: Any?): Boolean = other is PresetStatePayload && other.active == active &&
        other.count == count && other.missing.size == missing.size &&
        missing.indices.all { ItemStack.matches(missing[it], other.missing[it]) }

    override fun hashCode() = active * 31 + count

    companion object {
        val TYPE = CustomPacketPayload.Type<PresetStatePayload>(CobblemonPartySidebar.id("preset_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PresetStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.active)
                buffer.writeVarInt(payload.count)
                buffer.writeVarInt(payload.missing.size)
                payload.missing.forEach { ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, it) }
            },
            { buffer ->
                val active = buffer.readVarInt()
                val count = buffer.readVarInt()
                PresetStatePayload(active, count, List(buffer.readVarInt()) { ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer) })
            },
        )
    }
}
