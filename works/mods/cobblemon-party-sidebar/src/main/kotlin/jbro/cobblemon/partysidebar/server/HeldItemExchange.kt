package jbro.cobblemon.partysidebar.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.toitem.api.PokemonTransfers
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * Swaps a party Pokemon's held item with the item on the player's cursor, the way the inventory does with a slot.
 * Only while the player inventory or fusion menu is open; use that menu's cursor. Follows Cobblemon's rules for
 * handing an item to a Pokemon: not in battle, not while its entity is busy, and the held-item tags apply.
 */
object HeldItemExchange {
    fun handle(player: ServerPlayer, payload: HeldItemClickPayload) {
        if (!player.isAlive || player.isSpectator || !PokemonTransfers.supportsSidebarMenu(player.containerMenu)) return
        val pokemon = Cobblemon.storage.getParty(player).get(payload.slot) ?: return
        if (pokemon.uuid != payload.pokemonId || pokemon.entity?.isBusy == true) return
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.in_battle"), true)
            return
        }
        if (payload.quickMove) moveToInventory(player, pokemon) else swapWithCursor(player, pokemon)
        player.inventoryMenu.broadcastChanges()
        if (player.containerMenu !== player.inventoryMenu) player.containerMenu.broadcastChanges()
    }

    private fun moveToInventory(player: ServerPlayer, pokemon: Pokemon) {
        val held = pokemon.heldItem()
        if (held.isEmpty) return
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

    private fun swapWithCursor(player: ServerPlayer, pokemon: Pokemon) {
        val menu = player.containerMenu
        val carried = menu.carried
        val held = pokemon.heldItem()
        if (carried.isEmpty) {
            if (held.isEmpty) return
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
        val before = carried.count
        // Takes one item off the cursor stack; a cancelled swap leaves the stack untouched.
        val returned = pokemon.swapHeldItem(carried, decrement = true, aiCanDrop = false)
        if (carried.count == before) return
        if (carried.isEmpty) menu.carried = returned
        else if (!returned.isEmpty) player.inventory.placeItemBackInInventory(returned)
    }

    /** The same check Cobblemon makes when a player hands an item to a Pokemon in the world. */
    private fun isForbidden(stack: ItemStack): Boolean {
        val blacklisted = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD).any() &&
            stack.`is`(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD)
        val whitelisted = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD).none() ||
            stack.`is`(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD)
        return blacklisted || !whitelisted
    }
}
