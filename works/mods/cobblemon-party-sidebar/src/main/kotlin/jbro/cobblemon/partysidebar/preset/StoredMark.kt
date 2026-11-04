package jbro.cobblemon.partysidebar.preset

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * The mark an item carries while it belongs to a player's item storage: in the storage itself and while a Pokemon
 * borrows it. A borrowed item is a real held item, so battles, evolutions and other mods see it as usual; the mark only
 * decides where it goes when it leaves the Pokemon (back to the storage, not the inventory) and how it is drawn.
 *
 * Nothing keeps count of borrowed items: the marked stack is the only copy, so there is no record that could disagree
 * with it after a crash or a battle that ends early.
 */
object StoredMark {
    private const val KEY = "cobblemon_party_sidebar_stored"

    @JvmStatic fun isStored(stack: ItemStack): Boolean =
        !stack.isEmpty && stack.get(DataComponents.CUSTOM_DATA)?.contains(KEY) == true

    /** Marks [stack] in place and returns it. */
    @JvmStatic fun mark(stack: ItemStack): ItemStack {
        if (!stack.isEmpty && !isStored(stack)) CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.putBoolean(KEY, true) }
        return stack
    }

    /** Removes the mark from [stack] in place and returns it; other custom data stays. */
    @JvmStatic fun unmark(stack: ItemStack): ItemStack {
        if (isStored(stack)) CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.remove(KEY) }
        return stack
    }

    /** One unmarked item like [stack]: what a preset remembers a Pokemon borrowing. */
    fun prototype(stack: ItemStack): ItemStack = unmark(stack.copyWithCount(1))

    /** The same item and components once the mark is set aside. */
    fun sameItem(a: ItemStack, b: ItemStack): Boolean = ItemStack.isSameItemSameComponents(prototype(a), prototype(b))
}
