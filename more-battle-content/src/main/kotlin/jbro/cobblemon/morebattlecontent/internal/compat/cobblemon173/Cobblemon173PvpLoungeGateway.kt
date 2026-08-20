package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpArenaGeometry
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpArenaLease
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpLoungeGateway
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpReturnPoint
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSide
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpLoungeSpectatorStatePayload
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramProjection
import jbro.cobblemon.morebattlecontent.internal.spectate.RemoteSpectateResult
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleContentNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

internal class Cobblemon173PvpLoungeGateway(
    private val serverProvider: () -> MinecraftServer?,
    private val playerResolver: (UUID) -> ServerPlayer?,
) : PvpLoungeGateway {
    private val spectatorAnchors = LinkedHashMap<UUID, PvpArenaLease>()

    override fun ensureArena(lease: PvpArenaLease): Boolean {
        val level = serverProvider()?.getLevel(LEVEL_KEY) ?: return false
        PvpLoungeArenaBuilder.ensureBuilt(level, lease)
        return true
    }

    override fun capture(playerId: UUID): PvpReturnPoint? {
        val player = playerResolver(playerId) ?: return null
        return PvpReturnPoint(
            dimensionId = player.serverLevel().dimension().location().toString(),
            x = player.x,
            y = player.y,
            z = player.z,
            yaw = player.yRot,
            pitch = player.xRot,
            gameModeId = player.gameMode.gameModeForPlayer.name,
        )
    }

    override fun moveCompetitor(playerId: UUID, lease: PvpArenaLease, side: PvpRoomSide): Boolean {
        val player = playerResolver(playerId) ?: return false
        val level = loungeLevel(player) ?: return false
        val xOffset = if (side == PvpRoomSide.LEFT) -7.0 else 7.0
        val yaw = if (side == PvpRoomSide.LEFT) -90f else 90f
        player.teleportTo(level, lease.centerX + xOffset, lease.centerY.toDouble(), lease.centerZ.toDouble(), yaw, 0f)
        player.deltaMovement = Vec3.ZERO
        return true
    }

    override fun moveSpectator(playerId: UUID, lease: PvpArenaLease): Boolean {
        val player = playerResolver(playerId) ?: return false
        val level = loungeLevel(player) ?: return false
        if (!player.setGameMode(GameType.SPECTATOR)) return false
        player.teleportTo(level, lease.centerX.toDouble(), lease.centerY + 7.0, lease.centerZ + 13.0, 180f, 18f)
        player.deltaMovement = Vec3.ZERO
        spectatorAnchors[playerId] = lease
        if (ServerPlayNetworking.canSend(player, PvpLoungeSpectatorStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, PvpLoungeSpectatorStatePayload(true))
        }
        return true
    }

    override fun spectate(viewerId: UUID, targetId: UUID): Boolean {
        val viewer = playerResolver(viewerId) ?: return false
        val target = playerResolver(targetId) ?: return false
        return Cobblemon173RemoteSpectate.spectate(viewer, target) in setOf(
            RemoteSpectateResult.STARTED,
            RemoteSpectateResult.ALREADY_SPECTATING,
        )
    }

    override fun stopSpectating(viewerId: UUID, battleId: UUID) {
        BattleRegistry.getBattle(battleId)?.spectators?.remove(viewerId)
        playerResolver(viewerId)?.let { player ->
            ManagedBattleContentNetworking.hideFrom(player, battleId)
            BattleEndPacket().sendToPlayer(player)
        }
    }

    override fun showArenaHologram(
        playerId: UUID,
        battleId: UUID,
        lease: PvpArenaLease,
        perspective: PvpRoomSide,
    ) {
        val player = playerResolver(playerId) ?: return
        val opponentDirectionX = if (perspective == PvpRoomSide.LEFT) 1.0 else -1.0
        BattleArenaHologramNetworking.show(
            player,
            BattleArenaHologramProjection.centered(
                battleId = battleId,
                center = Vec3(lease.centerX.toDouble(), lease.centerY.toDouble(), lease.centerZ.toDouble()),
                opponentDirectionX = opponentDirectionX,
                opponentDirectionZ = 0.0,
                ledFloorRadius = PvpArenaGeometry.FLOOR_RADIUS_BLOCKS.toDouble(),
            ),
        )
    }

    override fun hideArenaHologram(playerId: UUID, battleId: UUID) {
        playerResolver(playerId)?.let { BattleArenaHologramNetworking.hide(it, battleId) }
    }

    override fun restore(playerId: UUID, point: PvpReturnPoint): Boolean {
        val player = playerResolver(playerId) ?: return false
        val location = ResourceLocation.tryParse(point.dimensionId) ?: return false
        val key = ResourceKey.create(Registries.DIMENSION, location)
        val level = player.server.getLevel(key) ?: return false
        spectatorAnchors.remove(playerId)
        player.setGameMode(GameType.byName(point.gameModeId, GameType.SURVIVAL) ?: GameType.SURVIVAL)
        player.teleportTo(level, point.x, point.y, point.z, point.yaw, point.pitch)
        player.deltaMovement = Vec3.ZERO
        if (ServerPlayNetworking.canSend(player, PvpLoungeSpectatorStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, PvpLoungeSpectatorStatePayload(false))
        }
        return true
    }

    /**
     * Last-resort exit used when a recorded return point is gone, for example because the server
     * restarted while somebody was standing in an arena. The game mode is left alone because nothing
     * here changes it on the way in.
     */
    fun restoreToOverworldSpawn(playerId: UUID): Boolean {
        val player = playerResolver(playerId) ?: return false
        val overworld = player.server.overworld()
        val spawn = overworld.sharedSpawnPos
        spectatorAnchors.remove(playerId)
        player.teleportTo(overworld, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0F, 0F)
        player.deltaMovement = Vec3.ZERO
        if (ServerPlayNetworking.canSend(player, PvpLoungeSpectatorStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, PvpLoungeSpectatorStatePayload(false))
        }
        return true
    }

    fun enforceSpectatorAnchors() {
        val level = serverProvider()?.getLevel(LEVEL_KEY) ?: return
        spectatorAnchors.toMap().forEach { (playerId, lease) ->
            val player = playerResolver(playerId) ?: return@forEach
            if (player.serverLevel() !== level || player.distanceToSqr(
                    lease.centerX.toDouble(),
                    lease.centerY + 7.0,
                    lease.centerZ + 13.0,
                ) > 16.0
            ) {
                player.teleportTo(level, lease.centerX.toDouble(), lease.centerY + 7.0, lease.centerZ + 13.0, 180f, 18f)
            }
            player.deltaMovement = Vec3.ZERO
        }
    }

    private fun loungeLevel(player: ServerPlayer): ServerLevel? = player.server.getLevel(LEVEL_KEY)

    internal companion object {
        val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "battle_lounge"),
        )
    }
}

private object PvpLoungeArenaBuilder {
    private const val RADIUS = PvpArenaGeometry.FLOOR_RADIUS_BLOCKS
    private const val HEIGHT = PvpArenaGeometry.WALL_HEIGHT_BLOCKS

    /** Single block at the exact centre of the floor. Also marks the arena as already built. */
    private val CENTER_BLOCK = Blocks.PEARLESCENT_FROGLIGHT

    fun ensureBuilt(level: ServerLevel, lease: PvpArenaLease) {
        // The centre block doubles as the "already built" marker.
        val marker = BlockPos(lease.centerX, lease.centerY - 1, lease.centerZ)
        if (level.getBlockState(marker).`is`(CENTER_BLOCK)) return
        val radiusSquared = RADIUS * RADIUS
        val innerWallSquared = (RADIUS - 1) * (RADIUS - 1)
        for (dx in -RADIUS..RADIUS) {
            for (dz in -RADIUS..RADIUS) {
                val distance = dx * dx + dz * dz
                if (distance > radiusSquared) continue
                val x = lease.centerX + dx
                val z = lease.centerZ + dz
                level.setBlock(BlockPos(x, lease.centerY - 2, z), Blocks.BLACK_CONCRETE.defaultBlockState(), 2)
                val floor = when {
                    dx < -1 -> Blocks.LIGHT_BLUE_STAINED_GLASS
                    dx > 1 -> Blocks.MAGENTA_STAINED_GLASS
                    else -> Blocks.BLACK_STAINED_GLASS
                }
                level.setBlock(BlockPos(x, lease.centerY - 1, z), floor.defaultBlockState(), 2)
                level.setBlock(
                    BlockPos(x, lease.centerY + HEIGHT, z),
                    Blocks.TINTED_GLASS.defaultBlockState(),
                    2,
                )
                if (distance >= innerWallSquared) {
                    val wall = if (dx <= 0) Blocks.LIGHT_BLUE_STAINED_GLASS else Blocks.MAGENTA_STAINED_GLASS
                    for (dy in 0 until HEIGHT) {
                        level.setBlock(BlockPos(x, lease.centerY + dy, z), wall.defaultBlockState(), 2)
                    }
                }
            }
        }
        level.setBlock(marker, CENTER_BLOCK.defaultBlockState(), 2)
    }
}
