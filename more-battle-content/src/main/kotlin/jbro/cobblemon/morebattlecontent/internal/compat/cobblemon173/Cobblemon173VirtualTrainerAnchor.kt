package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Gives Cobblemon's initial switch interpreter a trainer position without creating a persistent NPC.
 * The armor stand is deliberately never added to the world.
 */
internal object Cobblemon173VirtualTrainerAnchor {
    private const val DISTANCE_BLOCKS = 8.0

    fun create(player: ServerPlayer): ArmorStand {
        val ideal = position(player.position(), player.yRot)
        val level = player.serverLevel()
        val safe = Cobblemon173VirtualTrainerPlacement.select(
            ideal = ideal,
            referenceY = player.y,
            surfaceAt = { x, z -> surfaceAt(player, x, z) },
            hasClearance = { candidate ->
                level.worldBorder.isWithinBounds(BlockPos.containing(candidate)) &&
                    level.noCollision(
                        AABB.ofSize(
                            candidate.add(0.0, TRAINER_HEIGHT * 0.5, 0.0),
                            TRAINER_WIDTH,
                            TRAINER_HEIGHT,
                            TRAINER_WIDTH,
                        ),
                    )
            },
            hasLineOfSight = { candidate ->
                level.clip(
                    ClipContext(
                        player.eyePosition,
                        candidate.add(0.0, TRAINER_EYE_HEIGHT, 0.0),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        player,
                    ),
                ).type == HitResult.Type.MISS
            },
        ) ?: ideal
        return ArmorStand(level, safe.x, safe.y, safe.z).apply {
            isInvisible = true
            isInvulnerable = true
            isNoGravity = true
            isSilent = true
        }
    }

    internal fun position(origin: Vec3, yawDegrees: Float): Vec3 {
        val yawRadians = Math.toRadians(yawDegrees.toDouble())
        val forward = Vec3(-sin(yawRadians), 0.0, cos(yawRadians))
        return origin.add(forward.scale(DISTANCE_BLOCKS))
    }

    private fun surfaceAt(player: ServerPlayer, x: Int, z: Int): Double? {
        val level = player.serverLevel()
        if (!level.hasChunk(x shr 4, z shr 4)) return null
        val referenceY = floor(player.y).toInt()
        val position = BlockPos.MutableBlockPos(x, referenceY, z)
        for (blockY in (referenceY + SEARCH_ABOVE) downTo (referenceY - SEARCH_BELOW)) {
            position.set(x, blockY, z)
            val shape = level.getBlockState(position).getCollisionShape(level, position)
            if (!shape.isEmpty) {
                return blockY + shape.max(Direction.Axis.Y)
            }
        }
        return null
    }

    private const val SEARCH_ABOVE = 3
    private const val SEARCH_BELOW = 6
    private const val TRAINER_WIDTH = 0.72
    private const val TRAINER_HEIGHT = 1.8
    private const val TRAINER_EYE_HEIGHT = 1.62
}
