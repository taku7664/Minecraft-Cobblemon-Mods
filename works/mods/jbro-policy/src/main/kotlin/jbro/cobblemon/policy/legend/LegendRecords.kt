package jbro.cobblemon.policy.legend

import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/**
 * Which Legends each player caught in the wild themselves. Trades and restored items never land here, and releasing
 * the Pokemon does not clear the record: each player catches each Legend once.
 */
class LegendRecords private constructor(private val caught: MutableMap<UUID, MutableSet<String>>) : SavedData() {
    fun has(player: UUID, species: String): Boolean = caught[player]?.contains(species) == true

    /** Every Legend [player] caught themselves. */
    fun caughtBy(player: UUID): Set<String> = caught[player].orEmpty().toSet()

    fun add(player: UUID, species: String) {
        if (caught.getOrPut(player) { mutableSetOf() }.add(species)) setDirty()
    }

    /** Whether there was a record to remove. */
    fun remove(player: UUID, species: String): Boolean {
        val removed = caught[player]?.remove(species) == true
        if (removed) setDirty()
        return removed
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("players", CompoundTag().apply {
            for ((player, species) in caught) if (species.isNotEmpty()) {
                put(player.toString(), ListTag().apply { species.sorted().forEach { add(StringTag.valueOf(it)) } })
            }
        })
    }

    companion object {
        private val factory = Factory({ LegendRecords(mutableMapOf()) }, { tag, _ ->
            val players = tag.getCompound("players")
            LegendRecords(players.allKeys.mapNotNull { key ->
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
                uuid to players.getList(key, Tag.TAG_STRING.toInt()).map { it.asString }.toMutableSet()
            }.toMap(mutableMapOf()))
        }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): LegendRecords = server.overworld().dataStorage.computeIfAbsent(factory, "jbro_policy_legends")
    }
}
