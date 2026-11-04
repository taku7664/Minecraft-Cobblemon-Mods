package jbro.cobblemon.partysidebar.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.partysidebar.preset.PresetService
import jbro.cobblemon.partysidebar.preset.StorageKind
import jbro.cobblemon.partysidebar.preset.StorageLog
import jbro.cobblemon.partysidebar.preset.StorageMenu
import jbro.cobblemon.partysidebar.preset.StoredMark
import jbro.cobblemon.toitem.api.PokemonTransfers
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/**
 * Swaps a party Pokemon's held item with the item on the player's cursor, the way the inventory does with a slot.
 * Only while the player inventory, fusion menu or item storage is open; use that menu's cursor. Follows Cobblemon's
 * rules for handing an item to a Pokemon: not in battle, not while its entity is busy, and the held-item tags apply.
 *
 * A storage item on the cursor (only in the storage window) is lent: the Pokemon holds it with its mark. A lent item
 * that leaves the Pokemon goes back to the storage, never to the cursor or inventory.
 */
object HeldItemExchange {
    fun supportsMenu(menu: AbstractContainerMenu) = PokemonTransfers.supportsSidebarMenu(menu) || menu is StorageMenu

    fun handle(player: ServerPlayer, payload: HeldItemClickPayload) {
        if (!player.isAlive || player.isSpectator || !supportsMenu(player.containerMenu)) return
        val pokemon = Cobblemon.storage.getParty(player).get(payload.slot) ?: return
        if (pokemon.uuid != payload.pokemonId || pokemon.entity?.isBusy == true) return
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.in_battle"), true)
            return
        }
        if (payload.quickMove) moveToInventory(player, pokemon) else swapWithCursor(player, pokemon)
        PresetService.sync(player, force = false)
        player.inventoryMenu.broadcastChanges()
        if (player.containerMenu !== player.inventoryMenu) player.containerMenu.broadcastChanges()
    }

    private fun moveToInventory(player: ServerPlayer, pokemon: Pokemon) {
        val held = pokemon.heldItem()
        if (held.isEmpty) return
        if (StoredMark.isStored(held)) {
            returnToStorage(player, pokemon)
            return
        }
        // Beside the item storage, shift-click puts the item there, like shift-clicking an inventory item does.
        val menu = player.containerMenu
        if (menu is StorageMenu && menu.kind == StorageKind.ITEMS) {
            val storage = PresetService.storage(player)
            if (storage.canTake(listOf(held))) {
                val removed = pokemon.swapHeldItem(ItemStack.EMPTY, decrement = false)
                if (!pokemon.heldItem().isEmpty) return
                val what = StorageLog.describe(removed)
                if (storage.add(removed)) StorageLog.log(player, "deposit", what, heldBy(pokemon), "item_storage")
                else player.inventory.placeItemBackInInventory(removed)
                return
            }
        }
        val inventory = player.inventory
        if (inventory.getFreeSlot() < 0 && inventory.getSlotWithRemainingSpace(held) < 0) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.inventory_full"), true)
            return
        }
        val removed = pokemon.swapHeldItem(ItemStack.EMPTY, decrement = false)
        // Another mod may cancel the swap through HeldItemEvent; then the Pokemon still holds the item.
        if (!pokemon.heldItem().isEmpty) return
        inventory.placeItemBackInInventory(removed)
    }

    /** Takes a lent item off [pokemon] into the storage; its preset stops lending one. */
    private fun returnToStorage(player: ServerPlayer, pokemon: Pokemon) {
        val storage = PresetService.storage(player)
        if (!storage.canTake(listOf(pokemon.heldItem()))) {
            PresetService.reject(player, "storage_full")
            return
        }
        val removed = pokemon.swapHeldItem(ItemStack.EMPTY, decrement = false)
        if (!pokemon.heldItem().isEmpty) return
        StorageLog.log(player, "return", StorageLog.describe(removed), heldBy(pokemon), "item_storage")
        storage.add(removed)
        PresetService.forgetReference(player, pokemon)
    }

    private fun swapWithCursor(player: ServerPlayer, pokemon: Pokemon) {
        val menu = player.containerMenu
        val carried = menu.carried
        val held = pokemon.heldItem()
        if (carried.isEmpty) {
            if (held.isEmpty) return
            if (StoredMark.isStored(held)) {
                returnToStorage(player, pokemon)
                return
            }
            val removed = pokemon.swapHeldItem(ItemStack.EMPTY, decrement = false)
            if (!pokemon.heldItem().isEmpty) return
            menu.carried = removed
            return
        }
        if (isForbidden(carried)) {
            player.displayClientMessage(
                Component.translatable("cobblemon.held_item.forbidden", carried.hoverName, pokemon.getDisplayName()), true)
            return
        }
        if (ItemStack.isSameItemSameComponents(carried, held)) return
        val storage = PresetService.storage(player)
        if (StoredMark.isStored(held) && !storage.canTake(listOf(held))) {
            PresetService.reject(player, "storage_full")
            return
        }
        val before = carried.count
        // Takes one item off the cursor stack; a cancelled swap leaves the stack untouched.
        val returned = pokemon.swapHeldItem(carried, decrement = true, aiCanDrop = false)
        if (carried.count == before) return
        if (StoredMark.isStored(pokemon.heldItem())) {
            StorageLog.log(player, "lend", StorageLog.describe(pokemon.heldItem()), "cursor", heldBy(pokemon))
        }
        if (StoredMark.isStored(returned)) {
            StorageLog.log(player, "return", StorageLog.describe(returned), heldBy(pokemon), "item_storage")
            storage.add(returned)
            return
        }
        if (carried.isEmpty) menu.carried = returned
        else if (!returned.isEmpty) player.inventory.placeItemBackInInventory(returned)
    }

    private fun heldBy(pokemon: Pokemon) = "held(pokemon=${pokemon.uuid} species=${pokemon.species.resourceIdentifier})"

    /** The same check Cobblemon makes when a player hands an item to a Pokemon in the world. */
    private fun isForbidden(stack: ItemStack): Boolean {
        val blacklisted = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD).any() &&
            stack.`is`(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD)
        val whitelisted = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD).none() ||
            stack.`is`(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD)
        return blacklisted || !whitelisted
    }
}
