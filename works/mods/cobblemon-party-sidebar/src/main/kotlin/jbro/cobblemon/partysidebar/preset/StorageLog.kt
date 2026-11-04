package jbro.cobblemon.partysidebar.preset

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Server log lines for support questions ("my helmet is gone"), one per thing that moves in or out of a storage, in
 * the same shape as Cobblemon To Item's `[pokemon-transfer]` lines:
 *
 * `[party-storage] player=… playerUuid=… action=… what=… from=… to=…`
 *
 * [what] is an item id and count (with `stored` for an item carrying the storage mark), or `pokemon=<uuid>
 * species=<id>` for a Pokemon item. Actions: `deposit`/`withdraw` (the storage windows, worked out by comparing the
 * storage before and after each click), `lend`/`return` (held-item slots), `switch_*` (preset switches),
 * `leak_return`, `battle_use`/`battle_swap`.
 */
object StorageLog {
    fun describe(stack: ItemStack): String {
        if (stack.isEmpty) return "empty"
        PokemonItemData.pokemonTag(stack)?.let { tag ->
            return "pokemon=${tag.getUUID("UUID")} species=${tag.getString("Species")}"
        }
        val mark = if (StoredMark.isStored(stack)) " stored" else ""
        return "${BuiltInRegistries.ITEM.getKey(stack.item)} x${stack.count}$mark"
    }

    fun pokemon(uuid: UUID, species: String) = "pokemon=$uuid species=$species"

    fun log(player: ServerPlayer?, action: String, what: String, from: String, to: String) {
        CobblemonPartySidebar.LOGGER.info("[party-storage] player={} playerUuid={} action={} what={} from={} to={}",
            player?.scoreboardName ?: "-", player?.uuid ?: "-", action, what, from, to)
    }

    /** Runs [block] and logs what it put into or took out of [storage], slot by slot. */
    fun <T> tracked(player: ServerPlayer, storage: Container, kind: StorageKind, cause: String, block: () -> T): T {
        val before = List(storage.containerSize) { storage.getItem(it).copy() }
        val result = block()
        val name = if (kind == StorageKind.ITEMS) "item_storage" else "pokemon_storage"
        for (slot in before.indices) {
            val old = before[slot]
            val new = storage.getItem(slot)
            val where = "$name[$slot]"
            if (StoredMark.sameItem(old, new) || (old.isEmpty && new.isEmpty)) {
                val delta = new.count - old.count
                if (delta > 0) log(player, "deposit", describe(new.copyWithCount(delta)), cause, where)
                if (delta < 0) log(player, "withdraw", describe(old.copyWithCount(-delta)), where, cause)
                continue
            }
            if (!old.isEmpty) log(player, "withdraw", describe(old), where, cause)
            if (!new.isEmpty) log(player, "deposit", describe(new), cause, where)
        }
        return result
    }
}
