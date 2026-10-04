package jbro.cobblemon.policy.plaza

import java.util.UUID
import jbro.cobblemon.policy.JbroPolicy
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData

/** Where a player stood before entering the plaza. */
data class ReturnPoint(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/** Return points outlive restarts, so a player who logs out in the plaza can still go home. */
class PlazaReturnPoints private constructor(private val points: MutableMap<UUID, ReturnPoint>) : SavedData() {
    operator fun get(player: UUID): ReturnPoint? = points[player]

    operator fun set(player: UUID, point: ReturnPoint) {
        points[player] = point
        setDirty()
    }

    fun remove(player: UUID) {
        if (points.remove(player) != null) setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("entries", CompoundTag().apply {
            for ((player, point) in points) put(player.toString(), CompoundTag().apply {
                putString("dimension", point.dimension.location().toString())
                putDouble("x", point.x); putDouble("y", point.y); putDouble("z", point.z)
                putFloat("yaw", point.yaw); putFloat("pitch", point.pitch)
            })
        })
    }

    companion object {
        private val factory = Factory({ PlazaReturnPoints(mutableMapOf()) }, { tag, _ -> PlazaReturnPoints(load(tag)) }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): PlazaReturnPoints = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_plaza_returns")

        private fun load(tag: CompoundTag): MutableMap<UUID, ReturnPoint> {
            val entries = tag.getCompound("entries")
            val points = mutableMapOf<UUID, ReturnPoint>()
            for (key in entries.allKeys) {
                try {
                    val entry = entries.getCompound(key)
                    val dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.getString("dimension")))
                    points[UUID.fromString(key)] = ReturnPoint(dimension, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                        entry.getFloat("yaw"), entry.getFloat("pitch"))
                } catch (failure: RuntimeException) {
                    JbroPolicy.LOGGER.warn("Skipped an unreadable plaza return point for {}", key, failure)
                }
            }
            return points
        }
    }
}
