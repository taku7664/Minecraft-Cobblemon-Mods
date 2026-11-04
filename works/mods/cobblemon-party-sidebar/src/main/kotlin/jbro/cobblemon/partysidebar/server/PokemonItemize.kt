package jbro.cobblemon.partysidebar.server

import jbro.cobblemon.partysidebar.network.ItemizePayload
import jbro.cobblemon.partysidebar.network.RestorePayload
import jbro.cobblemon.partysidebar.preset.StorageKind
import jbro.cobblemon.partysidebar.preset.StorageLog
import jbro.cobblemon.partysidebar.preset.StorageMenu
import jbro.cobblemon.toitem.api.PokemonItemData
import jbro.cobblemon.toitem.api.PokemonTransfers
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * The sidebar selects the origin/destination; Cobblemon To Item owns all transfer rules.
 *
 * Shift-clicking a Pokemon beside the Pokemon storage or the fusion window sends it into that window rather than the
 * inventory: it is itemized into an empty inventory slot and then shift-clicked there by the window's own rule (the
 * Pokemon storage's first free slot; fusion's base slot, then its first empty material slot). When the window is full
 * the item stays in the inventory.
 */
object PokemonItemize {
    private val FUSION = ResourceLocation.fromNamespaceAndPath("pokefusion", "fusion")

    fun itemize(player: ServerPlayer, payload: ItemizePayload) {
        if (payload.toCursor) {
            PokemonTransfers.pickup(player, payload.slot, payload.pokemonId)
            return
        }
        val menu = player.containerMenu
        val staging = if (takesPokemon(menu)) menu.slots.indexOfFirst {
            it.container === player.inventory && it.containerSlot in 0 until 36 && !it.hasItem()
        } else -1
        if (!PokemonTransfers.itemize(player, payload.slot, payload.pokemonId, staging, dropOverflow = false) || staging < 0) return
        if (PokemonItemData.pokemonTag(menu.slots[staging].item)?.getUUID("UUID") != payload.pokemonId) return
        val item = menu.slots[staging].item.copy()
        menu.quickMoveStack(player, staging)
        // Cobblemon To Item logged the step into the inventory; this logs where the window took it.
        val moved = menu.slots[staging].item.isEmpty
        StorageLog.log(player, if (moved) "deposit" else "deposit_refused", StorageLog.describe(item), "inventory[${menu.slots[staging].containerSlot}]",
            if (menu is StorageMenu) "pokemon_storage" else "fusion")
        menu.broadcastChanges()
    }

    fun restore(player: ServerPlayer, payload: RestorePayload) {
        PokemonTransfers.restoreCursor(player, payload.slot, payload.pokemonId, payload.targetId)
    }

    private fun takesPokemon(menu: AbstractContainerMenu): Boolean =
        (menu is StorageMenu && menu.kind == StorageKind.POKEMON) || runCatching { BuiltInRegistries.MENU.getKey(menu.type) == FUSION }.getOrDefault(false)
}
