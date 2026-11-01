package jbro.cobblemon.mcc.pwt.server

import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData

/**
 * Tournament titles per player, and the winners' rewards still to be paid. A title is counted with its reward
 * pending in the same write, so a crash between the win and the payment pays it at the next join.
 */
class PwtSavedData internal constructor(
    private val titles: CompoundTag = CompoundTag(),
    private val unpaid: CompoundTag = CompoundTag(),
    /** Every token ever counted, so that a paid one still counts only once. */
    private val counted: CompoundTag = CompoundTag(),
) : SavedData() {
    fun titles(player: UUID): Int = titles.getInt(player.toString())

    /** Counts a won tournament once per [token] and keeps its reward owed until [paid]. */
    fun recordTitle(player: UUID, token: UUID) {
        val key = token.toString()
        if (counted.contains(key) || unpaid.contains(key)) return
        titles.putInt(player.toString(), titles(player) + 1)
        unpaid.putUUID(key, player)
        counted.putBoolean(key, true)
        setDirty()
    }

    /** A damaged entry is skipped: one bad line must not stop every player's join. */
    fun unpaid(player: UUID): List<UUID> =
        unpaid.allKeys.filter { unpaid.hasUUID(it) && unpaid.getUUID(it) == player }
            .mapNotNull { key -> runCatching { UUID.fromString(key) }.getOrNull() }

    fun paid(token: UUID) {
        unpaid.remove(token.toString())
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("titles", titles.copy())
        put("unpaid", unpaid.copy())
        put("counted", counted.copy())
    }

    companion object {
        private val factory = Factory({ PwtSavedData() }, { tag, _ ->
            PwtSavedData(tag.getCompound("titles").copy(), tag.getCompound("unpaid").copy(), tag.getCompound("counted").copy())
        }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): PwtSavedData = server.overworld().dataStorage.computeIfAbsent(factory, "pwt_titles")
    }
}
