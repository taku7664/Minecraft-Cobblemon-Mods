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
class PwtSavedData private constructor(private val titles: CompoundTag = CompoundTag(), private val unpaid: CompoundTag = CompoundTag()) : SavedData() {
    fun titles(player: UUID): Int = titles.getInt(player.toString())

    /** Counts a won tournament once per [token] and keeps its reward owed until [paid]. */
    fun recordTitle(player: UUID, token: UUID) {
        if (unpaid.contains(token.toString())) return
        titles.putInt(player.toString(), titles(player) + 1)
        unpaid.putUUID(token.toString(), player)
        setDirty()
    }

    fun unpaid(player: UUID): List<UUID> =
        unpaid.allKeys.filter { unpaid.getUUID(it) == player }.map(UUID::fromString)

    fun paid(token: UUID) {
        unpaid.remove(token.toString())
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = tag.apply {
        put("titles", titles.copy())
        put("unpaid", unpaid.copy())
    }

    companion object {
        private val factory = Factory({ PwtSavedData() }, { tag, _ ->
            PwtSavedData(tag.getCompound("titles").copy(), tag.getCompound("unpaid").copy())
        }, DataFixTypes.LEVEL)

        fun get(server: MinecraftServer): PwtSavedData = server.overworld().dataStorage.computeIfAbsent(factory, "pwt_titles")
    }
}
