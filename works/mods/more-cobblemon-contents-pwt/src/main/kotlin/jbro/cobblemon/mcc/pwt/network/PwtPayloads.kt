package jbro.cobblemon.mcc.pwt.network

import java.util.UUID
import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** What the tab asks for: [ENTER] with three party Pokémon, [FIGHT] the round's battle, [LEAVE] a run or its result. */
enum class PwtAction { REFRESH, ENTER, FIGHT, LEAVE }

/** A party Pokémon the challenger may enter. */
data class PwtPartyView(val id: String, val speciesId: String, val formId: String?, val level: Int,
    val heldItemId: String?, val legendaryClass: Boolean)

/** One bracket slot: a trainer, or the challenger when [player]. */
data class PwtEntrantView(val nameKey: String, val skin: String?, val generation: Int, val aceSpecies: String?, val player: Boolean)

/** The bracket as [jbro.cobblemon.mcc.pwt.system.PwtBracket] holds it, with each slot's look. */
data class PwtRunView(val entrants: List<PwtEntrantView>, val winners: List<List<Int>>, val round: Int,
    val finished: Boolean, val won: Boolean, val team: List<String>, val battling: Boolean)

data class PwtView(val nonce: String, val unlocked: Boolean, val party: List<PwtPartyView>, val run: PwtRunView?,
    val titles: Int, val bp: Long, val errorKey: String? = null,
    /** Set on the state sent after a battle, which brings the hub back on the PWT tab. */
    val openScreen: Boolean = false)

data class PwtStatePayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<PwtStatePayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "state_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PwtStatePayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.json, 32767) }, { PwtStatePayload(it.readUtf(32767)) })
    }
}

data class PwtIntentPayload(val nonce: UUID, val action: PwtAction, val picked: List<UUID> = emptyList()) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<PwtIntentPayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "intent_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PwtIntentPayload> = StreamCodec.of(
            { b, p ->
                b.writeUUID(p.nonce); b.writeEnum(p.action)
                b.writeVarInt(p.picked.size); p.picked.forEach(b::writeUUID)
            },
            { b ->
                val nonce = b.readUUID(); val action = b.readEnum(PwtAction::class.java)
                val count = b.readVarInt()
                require(count in 0..6) { "Too many picks" }
                PwtIntentPayload(nonce, action, List(count) { b.readUUID() })
            })
    }
}
