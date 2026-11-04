package jbro.cobblemon.partysidebar.network

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.ItemStack

/**
 * A sidebar button: switch to preset [index], add an empty preset, delete the current one, rename the current one to
 * [name], or open one of the storages.
 */
data class PresetActionPayload(val action: Action, val index: Int = 0, val name: String = "") : CustomPacketPayload {
    enum class Action { SELECT, ADD, DELETE, RENAME, OPEN_ITEMS, OPEN_POKEMON }

    override fun type() = TYPE

    companion object {
        private const val NAME_LIMIT = 64

        val TYPE = CustomPacketPayload.Type<PresetActionPayload>(CobblemonPartySidebar.id("preset_action"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PresetActionPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeEnum(payload.action)
                buffer.writeVarInt(payload.index)
                buffer.writeUtf(payload.name, NAME_LIMIT)
            },
            { buffer -> PresetActionPayload(buffer.readEnum(Action::class.java), buffer.readVarInt(), buffer.readUtf(NAME_LIMIT)) },
        )
    }
}

/**
 * What the sidebar shows of the presets: the current one of [count] and its [name] (blank for the default), and for
 * each party row the storage item its preset lends but the Pokemon is not holding ([missing], EMPTY when nothing is
 * missing) and whether the Pokemon it names is not in that row ([missingPokemon]).
 */
data class PresetStatePayload(
    val active: Int,
    val count: Int,
    val name: String,
    val missing: List<ItemStack>,
    val missingPokemon: List<Boolean>,
) : CustomPacketPayload {
    override fun type() = TYPE

    override fun equals(other: Any?): Boolean = other is PresetStatePayload && other.active == active &&
        other.count == count && other.name == name && other.missingPokemon == missingPokemon &&
        other.missing.size == missing.size && missing.indices.all { ItemStack.matches(missing[it], other.missing[it]) }

    override fun hashCode() = (active * 31 + count) * 31 + name.hashCode()

    companion object {
        val TYPE = CustomPacketPayload.Type<PresetStatePayload>(CobblemonPartySidebar.id("preset_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PresetStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeVarInt(payload.active)
                buffer.writeVarInt(payload.count)
                buffer.writeUtf(payload.name)
                buffer.writeVarInt(payload.missing.size)
                payload.missing.forEach { ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, it) }
                buffer.writeVarInt(payload.missingPokemon.size)
                payload.missingPokemon.forEach(buffer::writeBoolean)
            },
            { buffer ->
                val active = buffer.readVarInt()
                val count = buffer.readVarInt()
                val name = buffer.readUtf()
                val missing = List(buffer.readVarInt()) { ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer) }
                PresetStatePayload(active, count, name, missing, List(buffer.readVarInt()) { buffer.readBoolean() })
            },
        )
    }
}
