package jbro.cobblemon.mcc.internal.battle

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Who a managed trainer battle is against, kept from just before the engine starts it until [BattleEntryHold] holds
 * it, so the hold can send the versus intro. Only battles whose content stage names a League role get an intro.
 */
internal object BattleVersusIntros {
    data class Trainer(val nameKey: String, val skin: TrainerResourceSkin?)

    private val pending = ConcurrentHashMap<UUID, Trainer>()

    /** The League stages that play a versus intro, as `gym`, `elite_four` or `champion`, prefixed `hard_` on the hard route. */
    private val ROLES = setOf("gym", "elite_four", "tournament", "champion")

    fun prepare(playerId: UUID, nameKey: String, skin: TrainerResourceSkin?) {
        pending[playerId] = Trainer(nameKey, skin)
    }

    fun take(playerId: UUID): Trainer? = pending.remove(playerId)

    fun clear(playerId: UUID) {
        pending.remove(playerId)
    }

    /** The intro tier and whether it is the hard route, for a battle tagged [stage]; null when it gets no intro. */
    fun tierOf(stage: String?): Pair<String, Boolean>? {
        if (stage == null) return null
        val hard = stage.startsWith("hard_")
        val role = stage.removePrefix("hard_")
        return if (role in ROLES) role to hard else null
    }
}

/** Sent just before the hold of [battleId]: play the versus intro for [tier] against the trainer named by [nameKey]. */
internal data class BattleVersusPayload(
    val battleId: UUID,
    val tier: String,
    val hard: Boolean,
    val nameKey: String,
    val skin: ResourceLocation?,
    val slim: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleVersusPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleVersusPayload>(
            ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_versus"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleVersusPayload> = StreamCodec.of(
            { buffer, payload ->
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.battleId)
                buffer.writeUtf(payload.tier, 32)
                buffer.writeBoolean(payload.hard)
                buffer.writeUtf(payload.nameKey, 256)
                buffer.writeUtf(payload.skin?.toString().orEmpty(), 256)
                buffer.writeBoolean(payload.slim)
            },
            { buffer ->
                BattleVersusPayload(
                    UUIDUtil.STREAM_CODEC.decode(buffer),
                    buffer.readUtf(32),
                    buffer.readBoolean(),
                    buffer.readUtf(256),
                    buffer.readUtf(256).takeIf { it.isNotEmpty() }?.let(ResourceLocation::tryParse),
                    buffer.readBoolean(),
                )
            },
        )
    }
}
