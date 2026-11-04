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

    /** Moves as much of [stack] in as fits, shrinking it; true when anything moved. */
    fun insertPartly(stack: ItemStack): Boolean {
        val before = stack.count
        insert(items, StoredMark.mark(stack))
        if (stack.count == before) return false
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
 * The rows of the storage the window shows, starting at row [row]. The server moves it when the player scrolls and
 * the menu sends the newly visible items; the client's copy stays at row 0 and just holds what it is sent.
 */
class StorageWindow(private val storage: Container) : Container {
    var row = 0

    private fun index(slot: Int) = row * 9 + slot

    override fun getContainerSize() = StorageMenu.VISIBLE_ROWS * 9
    override fun isEmpty() = (0 until containerSize).all { getItem(it).isEmpty }
    override fun getItem(slot: Int): ItemStack = storage.getItem(index(slot))
    override fun removeItem(slot: Int, amount: Int): ItemStack = storage.removeItem(index(slot), amount)
    override fun removeItemNoUpdate(slot: Int): ItemStack = storage.removeItemNoUpdate(index(slot))
    override fun setItem(slot: Int, stack: ItemStack) = storage.setItem(index(slot), stack)
    override fun getMaxStackSize() = storage.maxStackSize
    override fun setChanged() = storage.setChanged()
    override fun stillValid(player: Player) = storage.stillValid(player)
    override fun clearContent() {}
}

/**
 * The storage window: [VISIBLE_ROWS] rows of a [PresetData.STORAGE_SIZE]-slot storage over the player inventory,
 * scrolled through [clickMenuButton] (the button id is the first row to show). Items leaving for the player inventory
 * lose their mark; a storage item on the cursor keeps it, so dropping it on a held-item slot lends it to that Pokemon
 * (see the held-item exchange).
 */
class StorageMenu private constructor(id: Int, inventory: Inventory, private val storage: Container, private val window: StorageWindow) :
    ChestMenu(TYPE, id, inventory, window, VISIBLE_ROWS) {

    constructor(id: Int, inventory: Inventory, storage: Container) : this(id, inventory, storage, StorageWindow(storage))

    constructor(id: Int, inventory: Inventory) : this(id, inventory, SimpleContainer(PresetData.STORAGE_SIZE))

    private val playerInventory = inventory

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id !in 0..MAX_ROW) return false
        window.row = id
        return true
    }

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        val storageSlot = slotId in 0 until VISIBLE_ROWS * 9
        // Thrown straight out of the storage, an item would reach the world still marked.
        if (clickType == ClickType.THROW && storageSlot) return
        if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) StoredMark.unmark(carried)
        // An item put on a storage stack must carry the mark to merge with it rather than swap.
        if (storageSlot && clickType == ClickType.PICKUP) StoredMark.mark(carried)
        super.clicked(slotId, button, clickType, player)
        stripInventory()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        // From the inventory, an item goes anywhere in the storage, not only into the rows on screen.
        if (index >= VISIBLE_ROWS * 9 && storage is StorageContainer) {
            val slot = slots[index]
            val stack = slot.item
            if (stack.isEmpty || !storage.insertPartly(stack)) {
                stripInventory()
                return ItemStack.EMPTY
            }
            slot.setChanged()
            stripInventory()
            return ItemStack.EMPTY
        }
        // Marked before the move so it merges with storage stacks; what does not fit is unmarked again below.
        if (index >= VISIBLE_ROWS * 9) StoredMark.mark(slots[index].item)
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
        const val VISIBLE_ROWS = 3
        const val MAX_ROW = PresetData.STORAGE_SIZE / 9 - VISIBLE_ROWS

        val TYPE: MenuType<StorageMenu> = MenuType(::StorageMenu, FeatureFlags.VANILLA_SET)

        fun register() {
            Registry.register(BuiltInRegistries.MENU, CobblemonPartySidebar.id("item_storage"), TYPE)
        }
    }
}
