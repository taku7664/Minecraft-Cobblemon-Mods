package jbro.cobblemon.policy.plaza

import java.nio.file.Files
import jbro.cobblemon.policy.JbroPolicy
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.LevelResource

/**
 * Chunks generated before the plaza had its own biome keep the old one; this repaints every chunk of the plaza area
 * with the plaza biome once. A fresh plaza is generated with it, so it is marked done without touching a chunk.
 */
object PlazaBiomeMigration {
    val BIOME: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, JbroPolicy.id("plaza"))
    private const val MIN_CHUNK = -24
    private const val MAX_CHUNK = 23
    private const val CHUNKS_PER_TICK = 4
    private val SIDE = MAX_CHUNK - MIN_CHUNK + 1

    private var level: ServerLevel? = null
    private var next = 0

    fun start(level: ServerLevel) {
        this.level = null
        val state = State.get(level)
        if (state.complete) return
        if (!hasSavedChunks(level)) {
            state.markComplete()
            return
        }
        if (level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(BIOME).isEmpty) {
            JbroPolicy.LOGGER.error("Plaza biome {} is not registered; the biome migration cannot start", BIOME.location())
            return
        }
        JbroPolicy.LOGGER.info("Starting the one-time plaza biome migration for {} chunks", SIDE * SIDE)
        this.level = level
        next = 0
    }

    fun tick() {
        val level = level ?: return
        val biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(BIOME)
        repeat(CHUNKS_PER_TICK) {
            if (next == SIDE * SIDE) {
                State.get(level).markComplete()
                this.level = null
                JbroPolicy.LOGGER.info("Plaza biome migration completed")
                return
            }
            val chunk = level.getChunk(MIN_CHUNK + next % SIDE, MIN_CHUNK + next / SIDE)
            chunk.fillBiomesFromNoise({ _, _, _, _ -> biome }, level.chunkSource.randomState().sampler())
            chunk.isUnsaved = true
            next++
        }
    }

    private fun hasSavedChunks(level: ServerLevel): Boolean {
        val region = DimensionType.getStorageFolder(Plaza.DIMENSION, level.server.getWorldPath(LevelResource.ROOT)).resolve("region")
        return Files.isDirectory(region) && Files.list(region).use { files -> files.anyMatch { it.toString().endsWith(".mca") } }
    }

    private class State(var complete: Boolean) : SavedData() {
        fun markComplete() {
            complete = true
            setDirty()
        }

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply { putBoolean("complete", complete) }

        companion object {
            private val factory = Factory({ State(false) }, { tag, _ -> State(tag.getBoolean("complete")) }, DataFixTypes.LEVEL)
            fun get(level: ServerLevel): State = level.server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_plaza_biome")
        }
    }
}
