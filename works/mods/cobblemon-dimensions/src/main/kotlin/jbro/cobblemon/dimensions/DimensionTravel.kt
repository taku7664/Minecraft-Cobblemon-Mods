package jbro.cobblemon.dimensions

import jbro.cobblemon.dimensions.portal.ParadoxPortalBlock
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap

/** Every way into and out of these dimensions goes through here, so the entry point is always kept. */
object DimensionTravel {
    /**
     * Sends [player] into [dimension] at the first solid ground found around [x], [z]. Coming from outside these
     * dimensions saves where the player stood; coming from another of them, or from a hub, keeps the point already saved.
     */
    fun enter(player: ServerPlayer, dimension: ModDimension, x: Int, z: Int): Boolean {
        val target = player.server.getLevel(dimension.key) ?: return false
        val landing = findLanding(target, x, z) ?: return false
        val from = player.serverLevel()
        if (!ModDimensions.isOurs(from) && !ModDimensions.isHub(from)) {
            EntryPoints.get(player.server)[player.uuid] = EntryPoint(from.dimension(), player.x, player.y, player.z, player.yRot, player.xRot)
        }
        player.teleportTo(target, landing.x + 0.5, landing.y.toDouble(), landing.z + 0.5, player.yRot, player.xRot)
        player.resetFallDistance()
        return true
    }

    /** Back to the saved entry point, or to the player's spawn when there is none or its world is gone. */
    fun returnHome(player: ServerPlayer) {
        val server = player.server
        val points = EntryPoints.get(server)
        val point = points[player.uuid]
        points.remove(player.uuid)
        val level = point?.let { server.getLevel(it.dimension) }
        if (point != null && level != null && !ModDimensions.isOurs(level)) {
            // A player who came in through a portal saved a point on its surface; land beside the frame instead.
            val (x, z) = besidePortal(level, point.x, point.y, point.z)
            val safeY = safeY(level, x, point.y, z)
            player.teleportTo(level, x, safeY, z, point.yaw, point.pitch)
        } else {
            val respawn = player.respawnPosition?.let { pos -> server.getLevel(player.respawnDimension)?.let { it to pos } }
            val (home, pos) = respawn ?: (server.overworld() to server.overworld().sharedSpawnPos)
            player.teleportTo(home, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot, player.xRot)
        }
        player.resetFallDistance()
    }

    /** Steps off a portal surface: the hole is 3x3, so four blocks out along x clears the frame. */
    private fun besidePortal(level: ServerLevel, x: Double, y: Double, z: Double): Pair<Double, Double> {
        val pos = BlockPos.containing(x, y, z)
        val onPortal = level.getBlockState(pos).block is ParadoxPortalBlock || level.getBlockState(pos.below()).block is ParadoxPortalBlock
        return if (onPortal) (x + 4) to z else x to z
    }

    /** The saved height when it is free, otherwise the ground above it; the world changed while the player was away. */
    private fun safeY(level: ServerLevel, x: Double, y: Double, z: Double): Double {
        val feet = BlockPos.containing(x, y, z)
        val free = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty &&
            level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty
        return if (free) y else surfaceY(level, feet.x, feet.z).toDouble()
    }

    /** Islands leave gaps of void, so landing searches outward in a spiral for a column with ground. */
    private fun findLanding(level: ServerLevel, x: Int, z: Int): BlockPos? {
        val step = 16
        for (ring in 0..16) {
            for (dx in -ring..ring) for (dz in -ring..ring) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != ring) continue
                val cx = x + dx * step
                val cz = z + dz * step
                val top = surfaceY(level, cx, cz)
                val ground = BlockPos(cx, top - 1, cz)
                if (top > level.minBuildHeight + 1 && level.getFluidState(ground).isEmpty) return BlockPos(cx, top, cz)
            }
        }
        return null
    }

    /**
     * The first free block above the ground at [x], [z]. `Level.getHeight` reports the world's bottom for a chunk that
     * is not loaded, and a new dimension has none, so this generates the chunk first.
     */
    private fun surfaceY(level: ServerLevel, x: Int, z: Int): Int =
        level.getChunk(x shr 4, z shr 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
}
