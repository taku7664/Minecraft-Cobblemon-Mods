package jbro.cobblemon.mcc.internal.battle

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** Which entry transition a held battle plays. */
enum class BattleEntryStyle(val id: String) {
    WILD("wild"),
    LEGENDARY("legendary"),
    TRAINER("trainer");

    companion object {
        fun fromId(id: String): BattleEntryStyle? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The server has approved [battleId] and holds it until the client's screen is covered. [species] is the opponent's
 * lead (the wild Pokémon, or the trainer's first) with its [form], so the client can colour a legendary transition and
 * music can pick the battle's track before the battle opens. [labels] are the lead's `alpha`, `legendary` and
 * `ultra_beast` marks.
 */
internal data class BattleEntryHoldPayload(
    val battleId: UUID,
    val style: BattleEntryStyle,
    val species: ResourceLocation?,
    val form: String,
    val labels: Set<String>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleEntryHoldPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleEntryHoldPayload>(entryId("battle_entry_hold"))
        private val OPTIONAL_ID = ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC)
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleEntryHoldPayload> = StreamCodec.of(
            { buffer, payload ->
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.battleId)
                buffer.writeUtf(payload.style.id)
                OPTIONAL_ID.encode(buffer, java.util.Optional.ofNullable(payload.species))
                buffer.writeUtf(payload.form)
                buffer.writeVarInt(payload.labels.size)
                payload.labels.forEach(buffer::writeUtf)
            },
            { buffer ->
                val battleId = UUIDUtil.STREAM_CODEC.decode(buffer)
                val style = BattleEntryStyle.fromId(buffer.readUtf()) ?: BattleEntryStyle.WILD
                val species = OPTIONAL_ID.decode(buffer).orElse(null)
                val form = buffer.readUtf()
                val labels = (0 until buffer.readVarInt().coerceIn(0, 8)).mapTo(LinkedHashSet()) { buffer.readUtf() }
                BattleEntryHoldPayload(battleId, style, species, form, labels)
            },
        )
    }
}

/** The client's screen is covered (or it shows no transition): the server may start [battleId]. */
internal data class BattleEntryReadyPayload(val battleId: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleEntryReadyPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleEntryReadyPayload>(entryId("battle_entry_ready"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleEntryReadyPayload> =
            UUIDUtil.STREAM_CODEC.map(::BattleEntryReadyPayload, BattleEntryReadyPayload::battleId).cast()
    }
}

/** The held battle will not start after all; the client clears its screen. */
internal data class BattleEntryCancelPayload(val battleId: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleEntryCancelPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleEntryCancelPayload>(entryId("battle_entry_release"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleEntryCancelPayload> =
            UUIDUtil.STREAM_CODEC.map(::BattleEntryCancelPayload, BattleEntryCancelPayload::battleId).cast()
    }
}

internal object BattleEntryNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleEntryHoldPayload.TYPE, BattleEntryHoldPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleEntryCancelPayload.TYPE, BattleEntryCancelPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BattleEntryReadyPayload.TYPE, BattleEntryReadyPayload.CODEC)
    }
}

private fun entryId(path: String) = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)
