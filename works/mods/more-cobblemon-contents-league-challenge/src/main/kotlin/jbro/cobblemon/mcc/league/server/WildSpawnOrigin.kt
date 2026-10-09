package jbro.cobblemon.mcc.league.server

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/** Keep the initial spawn origin across restarts and later /setworldspawn changes. No player levels are stored. */
class WildSpawnOrigin private constructor(val chunkX: Int, val chunkZ: Int) : SavedData() {
    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        putInt("schema_version", 1)
        putInt("chunk_x", chunkX)
        putInt("chunk_z", chunkZ)
    }

    companion object {
        fun get(server: MinecraftServer): WildSpawnOrigin {
            val overworld = server.overworld()
            val factory = Factory({
                val spawn = overworld.sharedSpawnPos
                WildSpawnOrigin(Math.floorDiv(spawn.x, 16), Math.floorDiv(spawn.z, 16)).apply { setDirty() }
            }, { tag, _ ->
                require(tag.getInt("schema_version") == 1 && tag.contains("chunk_x", 3) && tag.contains("chunk_z", 3)) {
                    "Invalid League wild spawn origin"
                }
                WildSpawnOrigin(tag.getInt("chunk_x"), tag.getInt("chunk_z"))
            }, DataFixTypes.LEVEL)
            return overworld.dataStorage.computeIfAbsent(factory, "league_wild_spawn_origin")
        }
    }
}
