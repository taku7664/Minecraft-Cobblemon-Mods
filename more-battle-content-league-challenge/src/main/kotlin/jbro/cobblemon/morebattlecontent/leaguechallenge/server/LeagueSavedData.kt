package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import java.util.UUID
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.*
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/** Keep raw entries until read, so corruption cannot erase another player's progress. */
class LeagueSavedData private constructor(private val entries: CompoundTag = CompoundTag(), private val preserved: CompoundTag? = null) : SavedData() {
    fun read(league: String, player: UUID): LeagueProgress {
        check(preserved == null) { "League storage unavailable" }
        val key = "$league/$player"
        if (!entries.contains(key)) return LeagueProgress()
        return LeagueProgressCodec.decode(LeagueRecordNbt.read(entries, key))
    }

    fun write(league: String, player: UUID, state: LeagueProgress) {
        check(preserved == null) { "League storage unavailable" }
        val encoded = LeagueProgressCodec.encode(state)
        LeagueProgressCodec.decode(encoded)
        LeagueRecordNbt.write(entries, "$league/$player", encoded)
        setDirty()
    }

    fun cancelInterruptedRuns() {
        for (key in entries.allKeys.toList()) {
            try {
                val state = LeagueProgressCodec.decode(LeagueRecordNbt.read(entries, key))
                if (state.run != null) {
                    LeagueRecordNbt.write(entries, key, LeagueProgressCodec.encode(state.copy(run = null, revision = state.revision + 1)))
                    setDirty()
                }
            } catch (_: RuntimeException) { /* Preserve corruption verbatim; read fails closed. */ }
        }
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = preserved?.copy() ?: tag.apply {
        put("entries", entries.copy())
    }

    companion object {
        private val factory = Factory({ LeagueSavedData() }, { tag, _ ->
            // Do not silently turn an unsupported/corrupt root into an empty successful save.
            if (!tag.contains("entries", 10)) LeagueSavedData(preserved = tag.copy())
            else LeagueSavedData(tag.getCompound("entries").copy())
        }, DataFixTypes.LEVEL)
        fun get(server: MinecraftServer): LeagueSavedData = server.overworld().dataStorage.computeIfAbsent(factory, "mbc_league_progress")
    }
}
