package jbro.cobblemon.mcc.internal.presentation

import java.util.Optional
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * [speaker] says [lines] to the client while its battle camera looks at the entity [focusEntityId] ([NO_FOCUS] leaves
 * the camera alone).
 */
internal data class BattleScenePayload(
    val focusEntityId: Int,
    val speaker: Component?,
    val lines: List<Component>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattleScenePayload> = TYPE

    companion object {
        const val NO_FOCUS = -1
        const val MAX_LINES = 8
        val TYPE = CustomPacketPayload.Type<BattleScenePayload>(
            ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_scene"))
        private val SPEAKER = ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC)
        private val LINES = ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LINES))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleScenePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BattleScenePayload::focusEntityId,
            SPEAKER, { Optional.ofNullable(it.speaker) },
            LINES, BattleScenePayload::lines,
        ) { focus, speaker, lines -> BattleScenePayload(focus, speaker.orElse(null), lines) }
    }
}

internal object BattleSceneNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleScenePayload.TYPE, BattleScenePayload.CODEC)
    }
}
