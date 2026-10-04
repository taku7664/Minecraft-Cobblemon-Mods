package jbro.cobblemon.policy.plaza

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import kotlin.math.abs
import kotlin.math.max

/** Keeps plaza returns on solid ground: saves the floor under a flying player and checks the spot on arrival. */
object SafeLanding {
    private const val MAX_DROP = 64
    private const val HORIZONTAL_RADIUS = 4
    private const val VERTICAL_RANGE = 4

    /** The y to save for [entity]: its own y on the ground or in water, else the first floor or water below. */
    fun groundY(level: ServerLevel, entity: Entity): Double {
        if (!entity.isPassenger && (entity.onGround() || entity.isInWater)) return entity.y
        val start = BlockPos.containing(entity.x, entity.y, entity.z)
        for (drop in 0..MAX_DROP) {
            val feet = start.below(drop)
            if (level.isOutsideBuildHeight(feet)) break
            if (!level.getFluidState(feet).isEmpty) return feet.y.toDouble()
            val below = feet.below()
            val shape = level.getBlockState(below).getCollisionShape(level, below)
            if (shape.isEmpty) continue
            val y = below.y + shape.max(Direction.Axis.Y)
            if (level.noCollision(entity, entity.boundingBox.move(0.0, y - entity.y, 0.0))) return y
        }
        return entity.y
    }

    /** The nearest spot around ([x], [y], [z]) where [entity] fits: on a dry solid floor first, then anywhere it fits. */
    fun find(level: ServerLevel, entity: Entity, x: Double, y: Double, z: Double): Triple<Double, Double, Double>? =
        search(level, entity, x, y, z, strict = true) ?: search(level, entity, x, y, z, strict = false)

    private fun search(level: ServerLevel, entity: Entity, x: Double, y: Double, z: Double, strict: Boolean): Triple<Double, Double, Double>? {
        for ((dx, dy, dz) in OFFSETS) {
            val cx = x + dx; val cy = y + dy; val cz = z + dz
            if (fits(level, entity, cx, cy, cz, strict)) return Triple(cx, cy, cz)
        }
        return null
    }

    private fun fits(level: ServerLevel, entity: Entity, x: Double, y: Double, z: Double, strict: Boolean): Boolean {
        val moved = entity.boundingBox.move(x - entity.x, y - entity.y, z - entity.z)
        val feet = BlockPos.containing(x, y, z)
        val head = BlockPos.containing(x, moved.maxY - 0.001, z)
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(head) || !level.worldBorder.isWithinBounds(feet)
            || !level.noCollision(entity, moved)) return false
        if (!strict) return true
        if (!level.getFluidState(feet).isEmpty || !level.getFluidState(head).isEmpty) return false
        val floor = BlockPos.containing(x, y - 0.01, z)
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
    }

    /** Nearest rings first, then the smallest height change, the same order the room mod searches in. */
    private val OFFSETS: List<Triple<Int, Int, Int>> = buildList {
        for (y in -VERTICAL_RANGE..VERTICAL_RANGE) for (x in -HORIZONTAL_RADIUS..HORIZONTAL_RADIUS)
            for (z in -HORIZONTAL_RADIUS..HORIZONTAL_RADIUS) add(Triple(x, y, z))
    }.sortedWith(compareBy<Triple<Int, Int, Int>>({ max(abs(it.first), abs(it.third)) }, { abs(it.second) },
        { abs(it.first) + abs(it.third) }, { it.second }, { it.first }, { it.third }))
}
