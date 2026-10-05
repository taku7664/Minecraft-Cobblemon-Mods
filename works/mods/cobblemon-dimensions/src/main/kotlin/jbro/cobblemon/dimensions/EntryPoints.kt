package jbro.cobblemon.dimensions

import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData

/** Where a player stood before entering one of these dimensions; falling out or a return portal sends them back. */
data class EntryPoint(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/** Entry points outlive restarts, so a player who logs out on an island can still fall home. */
class EntryPoints private constructor(private val points: MutableMap<UUID, EntryPoint>) : SavedData() {
    operator fun get(player: UUID): EntryPoint? = points[player]

    operator fun set(player: UUID, point: EntryPoint) {
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
        private val factory = Factory({ EntryPoints(mutableMapOf()) }, { tag, _ -> EntryPoints(load(tag)) }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): EntryPoints = server.overworld().dataStorage.computeIfAbsent(factory, "cobblemon_dimensions_entry_points")

        private fun load(tag: CompoundTag): MutableMap<UUID, EntryPoint> {
            val entries = tag.getCompound("entries")
            val points = mutableMapOf<UUID, EntryPoint>()
            for (key in entries.allKeys) {
                try {
                    val entry = entries.getCompound(key)
                    val dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.getString("dimension")))
                    points[UUID.fromString(key)] = EntryPoint(dimension, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                        entry.getFloat("yaw"), entry.getFloat("pitch"))
                } catch (failure: RuntimeException) {
                    CobblemonDimensions.LOGGER.warn("Skipped an unreadable entry point for {}", key, failure)
                }
            }
            return points
        }
    }
}
