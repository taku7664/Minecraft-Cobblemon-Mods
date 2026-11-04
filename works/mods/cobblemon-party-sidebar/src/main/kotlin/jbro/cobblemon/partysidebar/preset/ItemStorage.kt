package jbro.cobblemon.partysidebar.preset

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Registry
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack

/** A player's item storage as a container. Anything put in is marked as a storage item ([StoredMark]). */
class StorageContainer(private val items: NonNullList<ItemStack>, private val changed: () -> Unit) : Container {
    override fun getContainerSize() = items.size
    override fun isEmpty() = items.all(ItemStack::isEmpty)
    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, amount).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = StoredMark.mark(stack)
        if (stack.count > getMaxStackSize(stack)) stack.count = getMaxStackSize(stack)
        setChanged()
    }

    override fun setChanged() = changed()
    override fun stillValid(player: Player) = true
    override fun clearContent() {
        items.clear()
        setChanged()
    }

    /** Whether all of [stacks] fit, merging into equal stacks first. */
    fun canTake(stacks: List<ItemStack>): Boolean {
        val copy = NonNullList.withSize(items.size, ItemStack.EMPTY)
        items.forEachIndexed { index, stack -> copy[index] = stack.copy() }
        return stacks.all { insert(copy, StoredMark.mark(it.copy())).isEmpty }
    }

    /** Puts [stack] in whole, or leaves everything as it was and returns false. */
    fun add(stack: ItemStack): Boolean {
        if (stack.isEmpty) return true
        if (!canTake(listOf(stack))) return false
        insert(items, StoredMark.mark(stack.copy()))
        setChanged()
        return true
    }

    /** Takes one item like [prototype] out, still marked, or EMPTY when there is none. */
    fun takeOne(prototype: ItemStack): ItemStack {
        val slot = items.indexOfFirst { !it.isEmpty && StoredMark.sameItem(it, prototype) }
        if (slot < 0) return ItemStack.EMPTY
        return StoredMark.mark(removeItem(slot, 1))
    }

    private fun insert(target: NonNullList<ItemStack>, stack: ItemStack): ItemStack {
        for (existing in target) {
            if (stack.isEmpty) break
            if (existing.isEmpty || !ItemStack.isSameItemSameComponents(existing, stack)) continue
            val moved = minOf(stack.count, getMaxStackSize(existing) - existing.count)
            if (moved > 0) {
                existing.grow(moved)
                stack.shrink(moved)
            }
        }
        for (index in target.indices) {
            if (stack.isEmpty) break
            if (target[index].isEmpty) {
                val moved = minOf(stack.count, getMaxStackSize(stack))
                target[index] = stack.split(moved)
            }
        }
        return stack
    }
}

/**
 * The storage window: a chest of [PresetData.STORAGE_SIZE] slots over the player inventory, drawn with vanilla's
 * chest screen. Items leaving for the player inventory lose their mark; a storage item on the cursor keeps it, so
 * dropping it on a held-item slot lends it to that Pokemon (see the held-item exchange).
 */
class StorageMenu(id: Int, inventory: Inventory, container: Container) :
    ChestMenu(TYPE, id, inventory, container, PresetData.STORAGE_SIZE / 9) {

    constructor(id: Int, inventory: Inventory) : this(id, inventory, SimpleContainer(PresetData.STORAGE_SIZE))

    private val playerInventory = inventory

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        val storageSlot = slotId in 0 until PresetData.STORAGE_SIZE
        // Thrown straight out of the storage, an item would reach the world still marked.
        if (clickType == ClickType.THROW && storageSlot) return
        if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) StoredMark.unmark(carried)
        // An item put on a storage stack must carry the mark to merge with it rather than swap.
        if (storageSlot && clickType == ClickType.PICKUP) StoredMark.mark(carried)
        super.clicked(slotId, button, clickType, player)
        stripInventory()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        // Marked before the move so it merges with storage stacks; what does not fit is unmarked again below.
        if (index >= PresetData.STORAGE_SIZE) StoredMark.mark(slots[index].item)
        val result = super.quickMoveStack(player, index)
        stripInventory()
        return result
    }

    override fun removed(player: Player) {
        StoredMark.unmark(carried)
        super.removed(player)
    }

    /** Anything that reached the player inventory has been taken out of the storage. */
    private fun stripInventory() {
        for (slot in 0 until playerInventory.containerSize) StoredMark.unmark(playerInventory.getItem(slot))
    }

    companion object {
        val TYPE: MenuType<StorageMenu> = MenuType(::StorageMenu, FeatureFlags.VANILLA_SET)

        fun register() {
            Registry.register(BuiltInRegistries.MENU, CobblemonPartySidebar.id("item_storage"), TYPE)
        }
    }
}
