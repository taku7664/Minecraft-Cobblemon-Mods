package jbro.cobblemon.morebattlecontent.internal.presentation

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import kotlin.math.hypot
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

internal data class BattleArenaHologramProjection(
    val battleId: UUID,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val opponentDirectionX: Double,
    val opponentDirectionZ: Double,
    /**
     * Radius of the arena floor in blocks. A positive value selects the PvP arena floor lighting;
     * zero keeps the terrain hologram used by the Battle Tower and Battle Factory in the overworld.
     */
    val ledFloorRadius: Double = 0.0,
) {
    init {
        require(
            centerX.isFinite() && centerY.isFinite() && centerZ.isFinite() &&
                opponentDirectionX.isFinite() && opponentDirectionZ.isFinite(),
        )
        require(hypot(opponentDirectionX, opponentDirectionZ) >= MIN_DIRECTION_LENGTH)
        require(ledFloorRadius.isFinite() && ledFloorRadius >= 0.0)
    }

    companion object {
        fun between(battleId: UUID, perspectivePosition: Vec3, opponentPosition: Vec3): BattleArenaHologramProjection =
            centered(
                battleId = battleId,
                center = perspectivePosition.add(opponentPosition).scale(0.5),
                opponentDirectionX = opponentPosition.x - perspectivePosition.x,
                opponentDirectionZ = opponentPosition.z - perspectivePosition.z,
            )

        fun centered(
            battleId: UUID,
            center: Vec3,
            opponentDirectionX: Double,
            opponentDirectionZ: Double,
            ledFloorRadius: Double = 0.0,
        ): BattleArenaHologramProjection {
            val length = hypot(opponentDirectionX, opponentDirectionZ)
            val directionX = if (length.isFinite() && length >= MIN_DIRECTION_LENGTH) opponentDirectionX / length else 0.0
            val directionZ = if (length.isFinite() && length >= MIN_DIRECTION_LENGTH) opponentDirectionZ / length else 1.0
            return BattleArenaHologramProjection(
                battleId = battleId,
                centerX = center.x,
                centerY = center.y,
                centerZ = center.z,
                opponentDirectionX = directionX,
                opponentDirectionZ = directionZ,
                ledFloorRadius = ledFloorRadius,
            )
        }
    }
}

internal data class ShowBattleArenaHologramPayload(
    val projection: BattleArenaHologramProjection,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShowBattleArenaHologramPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShowBattleArenaHologramPayload>(id("battle_arena_hologram_show"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShowBattleArenaHologramPayload> = StreamCodec.of(
            { buffer, payload ->
                val projection = payload.projection
                buffer.writeUUID(projection.battleId)
                buffer.writeDouble(projection.centerX)
                buffer.writeDouble(projection.centerY)
                buffer.writeDouble(projection.centerZ)
                buffer.writeDouble(projection.opponentDirectionX)
                buffer.writeDouble(projection.opponentDirectionZ)
                buffer.writeDouble(projection.ledFloorRadius)
            },
            { buffer ->
                ShowBattleArenaHologramPayload(
                    BattleArenaHologramProjection(
                        battleId = buffer.readUUID(),
                        centerX = buffer.readDouble(),
                        centerY = buffer.readDouble(),
                        centerZ = buffer.readDouble(),
                        opponentDirectionX = buffer.readDouble(),
                        opponentDirectionZ = buffer.readDouble(),
                        ledFloorRadius = buffer.readDouble(),
                    ),
                )
            },
        )
    }
}

internal data class HideBattleArenaHologramPayload(
    val battleId: UUID,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HideBattleArenaHologramPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HideBattleArenaHologramPayload>(id("battle_arena_hologram_hide"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HideBattleArenaHologramPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUUID(payload.battleId) },
            { buffer -> HideBattleArenaHologramPayload(buffer.readUUID()) },
        )
    }
}

internal object BattleArenaHologramNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShowBattleArenaHologramPayload.TYPE, ShowBattleArenaHologramPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HideBattleArenaHologramPayload.TYPE, HideBattleArenaHologramPayload.CODEC)
    }

    fun showBetween(player: ServerPlayer, battleId: UUID, perspectivePosition: Vec3, opponentPosition: Vec3) {
        show(player, BattleArenaHologramProjection.between(battleId, perspectivePosition, opponentPosition))
    }

    fun show(player: ServerPlayer, projection: BattleArenaHologramProjection) {
        try {
            if (ServerPlayNetworking.canSend(player, ShowBattleArenaHologramPayload.TYPE)) {
                ServerPlayNetworking.send(player, ShowBattleArenaHologramPayload(projection))
            }
        } catch (exception: RuntimeException) {
            MoreBattleContent.LOGGER.warn(
                "Arena hologram show failed for player {} and battle {}; continuing without the optional effect",
                player.uuid,
                projection.battleId,
                exception,
            )
        }
    }

    fun hide(player: ServerPlayer, battleId: UUID) {
        try {
            if (ServerPlayNetworking.canSend(player, HideBattleArenaHologramPayload.TYPE)) {
                ServerPlayNetworking.send(player, HideBattleArenaHologramPayload(battleId))
            }
        } catch (exception: RuntimeException) {
            MoreBattleContent.LOGGER.warn(
                "Arena hologram hide failed for player {} and battle {}; client disconnect cleanup remains available",
                player.uuid,
                battleId,
                exception,
            )
        }
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private const val MIN_DIRECTION_LENGTH = 0.000001
