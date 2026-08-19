package jbro.cobblemon.morebattlecontent.internal.shadow

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

internal data class ShadowTrainerProjection(
    val battleId: UUID,
    val profileId: UUID,
    val profileName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
) {
    init {
        require(profileName.isNotBlank() && profileName.length <= MAX_PROFILE_NAME_LENGTH)
        require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite())
    }
}

internal class ShadowTrainerProjectionState {
    private var projection: ShadowTrainerProjection? = null

    fun show(next: ShadowTrainerProjection) {
        projection = next
    }

    fun hide(battleId: UUID) {
        if (projection?.battleId == battleId) projection = null
    }

    fun clear() {
        projection = null
    }

    fun current(): ShadowTrainerProjection? = projection
}

internal data class ShowShadowTrainerPayload(
    val projection: ShadowTrainerProjection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShowShadowTrainerPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShowShadowTrainerPayload>(id("shadow_trainer_show"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShowShadowTrainerPayload> = StreamCodec.of(
            { buffer, payload ->
                val projection = payload.projection
                buffer.writeUUID(projection.battleId)
                buffer.writeUUID(projection.profileId)
                buffer.writeUtf(projection.profileName, MAX_PROFILE_NAME_LENGTH)
                buffer.writeDouble(projection.x)
                buffer.writeDouble(projection.y)
                buffer.writeDouble(projection.z)
                buffer.writeFloat(projection.yaw)
            },
            { buffer ->
                ShowShadowTrainerPayload(
                    ShadowTrainerProjection(
                        battleId = buffer.readUUID(),
                        profileId = buffer.readUUID(),
                        profileName = buffer.readUtf(MAX_PROFILE_NAME_LENGTH),
                        x = buffer.readDouble(),
                        y = buffer.readDouble(),
                        z = buffer.readDouble(),
                        yaw = buffer.readFloat(),
                    ),
                )
            },
        )
    }
}

internal data class HideShadowTrainerPayload(
    val battleId: UUID,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HideShadowTrainerPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HideShadowTrainerPayload>(id("shadow_trainer_hide"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HideShadowTrainerPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUUID(payload.battleId) },
            { buffer -> HideShadowTrainerPayload(buffer.readUUID()) },
        )
    }
}

internal object ShadowTrainerProjectionNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShowShadowTrainerPayload.TYPE, ShowShadowTrainerPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HideShadowTrainerPayload.TYPE, HideShadowTrainerPayload.CODEC)
    }

    fun show(player: ServerPlayer, battleId: UUID, position: Vec3) {
        if (!ServerPlayNetworking.canSend(player, ShowShadowTrainerPayload.TYPE)) return
        ServerPlayNetworking.send(
            player,
            ShowShadowTrainerPayload(
                ShadowTrainerProjection(
                    battleId = battleId,
                    profileId = player.gameProfile.id,
                    profileName = player.gameProfile.name,
                    x = position.x,
                    y = position.y,
                    z = position.z,
                    yaw = player.yRot + HALF_TURN_DEGREES,
                ),
            ),
        )
    }

    fun hide(player: ServerPlayer, battleId: UUID) {
        if (ServerPlayNetworking.canSend(player, HideShadowTrainerPayload.TYPE)) {
            ServerPlayNetworking.send(player, HideShadowTrainerPayload(battleId))
        }
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private const val MAX_PROFILE_NAME_LENGTH = 16
private const val HALF_TURN_DEGREES = 180.0F
