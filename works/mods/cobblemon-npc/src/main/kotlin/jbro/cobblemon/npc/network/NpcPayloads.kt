package jbro.cobblemon.npc.network

import jbro.cobblemon.npc.CobblemonNpc
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private const val TEXT = 1024
private const val JSON = 262_144
private const val MAX_ITEMS = 64

private fun <T : CustomPacketPayload> type(path: String) = CustomPacketPayload.Type<T>(CobblemonNpc.id(path))

private fun FriendlyByteBuf.writeStrings(values: List<String>) {
    require(values.size <= MAX_ITEMS) { "Too many entries" }
    writeVarInt(values.size)
    values.forEach { writeUtf(it, TEXT) }
}

private fun FriendlyByteBuf.readStrings(): List<String> {
    val size = readVarInt().also { require(it in 0..MAX_ITEMS) }
    return List(size) { readUtf(TEXT) }
}

private fun RegistryFriendlyByteBuf.writeComponent(value: Component) = ComponentSerialization.TRUSTED_STREAM_CODEC.encode(this, value)

private fun RegistryFriendlyByteBuf.readComponent(): Component = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(this)

private fun RegistryFriendlyByteBuf.writeComponents(values: List<Component>) {
    require(values.size <= MAX_ITEMS) { "Too many entries" }
    writeVarInt(values.size)
    values.forEach { writeComponent(it) }
}

private fun RegistryFriendlyByteBuf.readComponents(): List<Component> {
    val size = readVarInt().also { require(it in 0..MAX_ITEMS) }
    return List(size) { readComponent() }
}

private fun <T : CustomPacketPayload> codec(write: (RegistryFriendlyByteBuf, T) -> Unit, read: (RegistryFriendlyByteBuf) -> T):
    StreamCodec<RegistryFriendlyByteBuf, T> = StreamCodec.of(write, read)

/**
 * A node to show: who speaks, the pages to read and the answers to pick from. [npcEntityId] is the speaking NPC's
 * network id, which the client's battle camera turns to ([NO_NPC] for a talk opened without one). The texts are
 * components, so a talk built in code can send translation keys the client reads in its own language.
 */
data class DialogueShowPayload(
    val session: Int,
    val speaker: Component,
    val skin: String,
    val lines: List<Component>,
    val choices: List<Component>,
    val npcEntityId: Int = NO_NPC,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val NO_NPC = -1
        val TYPE = type<DialogueShowPayload>("dialogue_show")
        val CODEC = codec<DialogueShowPayload>({ b, p ->
            b.writeVarInt(p.session); b.writeComponent(p.speaker); b.writeUtf(p.skin, TEXT)
            b.writeComponents(p.lines); b.writeComponents(p.choices); b.writeVarInt(p.npcEntityId)
        }, { b -> DialogueShowPayload(b.readVarInt(), b.readComponent(), b.readUtf(TEXT), b.readComponents(), b.readComponents(), b.readVarInt()) })
    }
}

data class DialogueClosePayload(val session: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueClosePayload>("dialogue_close")
        val CODEC = codec<DialogueClosePayload>({ b, p -> b.writeVarInt(p.session) }, { b -> DialogueClosePayload(b.readVarInt()) })
    }
}

/** The player read the node's last page; [choice] is the index into the shown answers, or -1 without any. */
data class DialogueAnswerPayload(val session: Int, val choice: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueAnswerPayload>("dialogue_answer")
        val CODEC = codec<DialogueAnswerPayload>({ b, p -> b.writeVarInt(p.session); b.writeVarInt(p.choice + 1) },
            { b -> DialogueAnswerPayload(b.readVarInt(), b.readVarInt() - 1) })
    }
}

/** The player closed the dialogue box. */
data class DialogueLeavePayload(val session: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueLeavePayload>("dialogue_leave")
        val CODEC = codec<DialogueLeavePayload>({ b, p -> b.writeVarInt(p.session) }, { b -> DialogueLeavePayload(b.readVarInt()) })
    }
}

/** An NPC's settings as the wand opens them, with the dialogues there are to pick. */
data class NpcSettingsPayload(
    val entityId: Int,
    val name: String,
    val skin: String,
    val dialogue: String,
    val dialogues: List<String>,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<NpcSettingsPayload>("npc_settings")
        val CODEC = codec<NpcSettingsPayload>({ b, p ->
            b.writeVarInt(p.entityId); b.writeUtf(p.name, TEXT); b.writeUtf(p.skin, TEXT); b.writeUtf(p.dialogue, TEXT)
            b.writeStrings(p.dialogues.take(MAX_ITEMS))
        }, { b -> NpcSettingsPayload(b.readVarInt(), b.readUtf(TEXT), b.readUtf(TEXT), b.readUtf(TEXT), b.readStrings()) })
    }
}

/** Saves an NPC's settings; [remove] takes the NPC away instead. */
data class NpcSavePayload(val entityId: Int, val name: String, val skin: String, val dialogue: String, val remove: Boolean) :
    CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<NpcSavePayload>("npc_save")
        val CODEC = codec<NpcSavePayload>({ b, p ->
            b.writeVarInt(p.entityId); b.writeUtf(p.name, TEXT); b.writeUtf(p.skin, TEXT); b.writeUtf(p.dialogue, TEXT)
            b.writeBoolean(p.remove)
        }, { b -> NpcSavePayload(b.readVarInt(), b.readUtf(TEXT), b.readUtf(TEXT), b.readUtf(TEXT), b.readBoolean()) })
    }
}

/** Asks for a dialogue file to edit. */
data class DialogueFetchPayload(val id: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueFetchPayload>("dialogue_fetch")
        val CODEC = codec<DialogueFetchPayload>({ b, p -> b.writeUtf(p.id, TEXT) }, { b -> DialogueFetchPayload(b.readUtf(TEXT)) })
    }
}

/** A dialogue file to edit; a new id comes with a starting template. */
data class DialogueSourcePayload(val id: String, val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueSourcePayload>("dialogue_source")
        val CODEC = codec<DialogueSourcePayload>({ b, p -> b.writeUtf(p.id, TEXT); b.writeUtf(p.json, JSON) },
            { b -> DialogueSourcePayload(b.readUtf(TEXT), b.readUtf(JSON)) })
    }
}

/** Saves a dialogue file; [preview] then plays it to the editor. */
data class DialogueStorePayload(val id: String, val json: String, val preview: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<DialogueStorePayload>("dialogue_store")
        val CODEC = codec<DialogueStorePayload>({ b, p -> b.writeUtf(p.id, TEXT); b.writeUtf(p.json, JSON); b.writeBoolean(p.preview) },
            { b -> DialogueStorePayload(b.readUtf(TEXT), b.readUtf(JSON), b.readBoolean()) })
    }
}

/** How a save went: a translation key and its arguments. */
data class EditorResultPayload(val ok: Boolean, val key: String, val args: List<String>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<EditorResultPayload>("editor_result")
        val CODEC = codec<EditorResultPayload>({ b, p -> b.writeBoolean(p.ok); b.writeUtf(p.key, TEXT); b.writeStrings(p.args) },
            { b -> EditorResultPayload(b.readBoolean(), b.readUtf(TEXT), b.readStrings()) })
    }
}

object NpcPayloads {
    fun register() {
        PayloadTypeRegistry.playS2C().register(DialogueShowPayload.TYPE, DialogueShowPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(DialogueClosePayload.TYPE, DialogueClosePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(NpcSettingsPayload.TYPE, NpcSettingsPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(DialogueSourcePayload.TYPE, DialogueSourcePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(EditorResultPayload.TYPE, EditorResultPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DialogueAnswerPayload.TYPE, DialogueAnswerPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DialogueLeavePayload.TYPE, DialogueLeavePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(NpcSavePayload.TYPE, NpcSavePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DialogueFetchPayload.TYPE, DialogueFetchPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(DialogueStorePayload.TYPE, DialogueStorePayload.CODEC)
    }
}
