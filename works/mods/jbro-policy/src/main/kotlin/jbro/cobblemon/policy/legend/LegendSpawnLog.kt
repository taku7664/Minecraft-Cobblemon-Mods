package jbro.cobblemon.policy.legend

import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/**
 * What became of each Legend spawned for a player, kept for a week. It is what lets a despawn be told from a capture
 * or a faint when the player asks for the Legend back ([LegendRestore]).
 */
class LegendSpawnLog private constructor(private val entries: MutableMap<UUID, Entry>) : SavedData() {
    enum class State {
        /** Still in the world, perhaps in an unloaded chunk. */
        ALIVE,
        /** The despawner removed it. */
        DESPAWNED,
        /** Caught, fainted or killed. */
        ENDED,
        /** Despawned and already spawned again. */
        RESTORED,
    }

    data class Entry(
        val pokemon: UUID,
        val owner: UUID,
        val legend: String,
        val shiny: Boolean,
        val spawnedAt: Long,
        val state: State = State.ALIVE,
        /** How many restores led to this spawn; a restore of a restore counts up. */
        val restores: Int = 0,
        val dimension: String = "",
        val x: Int = 0,
        val z: Int = 0,
    )

    fun of(owner: UUID): List<Entry> = entries.values.filter { it.owner == owner }

    fun add(entry: Entry) {
        val oldest = entry.spawnedAt - KEPT_MILLIS
        entries.values.removeIf { it.spawnedAt < oldest }
        entries[entry.pokemon] = entry
        setDirty()
    }

    /** An entity removal or a capture changes only what is still alive, whichever of the two comes first. */
    fun end(pokemon: UUID, state: State) {
        val entry = entries[pokemon] ?: return
        if (entry.state == State.ENDED || (entry.state != State.ALIVE && state != State.ENDED)) return
        entries[pokemon] = entry.copy(state = state)
        setDirty()
    }

    fun moved(pokemon: UUID, dimension: String, x: Int, z: Int) {
        val entry = entries[pokemon] ?: return
        entries[pokemon] = entry.copy(dimension = dimension, x = x, z = z)
        setDirty()
    }

    fun restored(pokemon: UUID, into: UUID) {
        val entry = entries[pokemon] ?: return
        entries[pokemon] = entry.copy(state = State.RESTORED)
        entries[into]?.let { entries[into] = it.copy(restores = entry.restores + 1) }
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("entries", ListTag().apply {
            for (entry in entries.values) add(CompoundTag().apply {
                putUUID("pokemon", entry.pokemon)
                putUUID("owner", entry.owner)
                putString("legend", entry.legend)
                putBoolean("shiny", entry.shiny)
                putLong("spawnedAt", entry.spawnedAt)
                putString("state", entry.state.name)
                putInt("restores", entry.restores)
                putString("dimension", entry.dimension)
                putInt("x", entry.x)
                putInt("z", entry.z)
            })
        })
    }

    companion object {
        private const val KEPT_MILLIS = 7L * 24 * 60 * 60 * 1000

        private val factory = Factory({ LegendSpawnLog(mutableMapOf()) }, { tag, _ ->
            LegendSpawnLog(tag.getList("entries", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }.associate { entry ->
                entry.getUUID("pokemon") to Entry(
                    entry.getUUID("pokemon"), entry.getUUID("owner"), entry.getString("legend"), entry.getBoolean("shiny"),
                    entry.getLong("spawnedAt"), State.valueOf(entry.getString("state")), entry.getInt("restores"),
                    entry.getString("dimension"), entry.getInt("x"), entry.getInt("z"),
                )
            }.toMutableMap())
        }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): LegendSpawnLog = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_legend_spawns")
    }
}
