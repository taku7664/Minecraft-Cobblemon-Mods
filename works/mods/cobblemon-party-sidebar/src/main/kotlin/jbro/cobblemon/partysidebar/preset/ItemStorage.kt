package jbro.cobblemon.partysidebar.preset

import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.toitem.api.PokemonItemData
import jbro.cobblemon.toitem.api.PokemonTransfers
import net.minecraft.core.NonNullList
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
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
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/** Which of a player's two storages: held items for lending, or itemized Pokemon for presets. */
enum class StorageKind { ITEMS, POKEMON }

/**
 * One of a player's storages as a container. In the item storage anything put in is marked as a storage item
 * ([StoredMark]); the Pokemon storage holds Cobblemon To Item's Pokemon items as they are.
 */
class StorageContainer(private val items: NonNullList<ItemStack>, private val kind: StorageKind, private val changed: () -> Unit) : Container {
    override fun getContainerSize() = items.size
    override fun isEmpty() = items.all(ItemStack::isEmpty)
    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, amount).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot).also { setChanged() }

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = mark(stack)
        if (stack.count > getMaxStackSize(stack)) stack.count = getMaxStackSize(stack)
        setChanged()
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack) = accepts(stack)
    override fun setChanged() = changed()
    override fun stillValid(player: Player) = true
    override fun clearContent() {
        items.clear()
        setChanged()
    }

    fun accepts(stack: ItemStack) = kind == StorageKind.ITEMS || PokemonItemData.isPokemonItem(stack)

    private fun mark(stack: ItemStack) = if (kind == StorageKind.ITEMS) StoredMark.mark(stack) else stack

    fun freeSlots() = items.count(ItemStack::isEmpty)

    /** Whether all of [stacks] fit, merging into equal stacks first. */
    fun canTake(stacks: List<ItemStack>): Boolean {
        val copy = NonNullList.withSize(items.size, ItemStack.EMPTY)
        items.forEachIndexed { index, stack -> copy[index] = stack.copy() }
        return stacks.all { insert(copy, mark(it.copy())).isEmpty }
    }

    /** Puts [stack] in whole, or leaves everything as it was and returns false. */
    fun add(stack: ItemStack): Boolean {
        if (stack.isEmpty) return true
        if (!accepts(stack) || !canTake(listOf(stack))) return false
        insert(items, mark(stack.copy()))
        setChanged()
        return true
    }

    /** Moves as much of [stack] in as fits, shrinking it; true when anything moved. */
    fun insertPartly(stack: ItemStack): Boolean {
        if (!accepts(stack)) return false
        val before = stack.count
        insert(items, mark(stack))
        if (stack.count == before) return false
        setChanged()
        return true
    }

    /** Takes one item like [prototype] out, still marked, or EMPTY when there is none. */
    fun takeOne(prototype: ItemStack): ItemStack {
        val slot = items.indexOfFirst { !it.isEmpty && StoredMark.sameItem(it, prototype) }
        if (slot < 0) return ItemStack.EMPTY
        return mark(removeItem(slot, 1))
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

/** A Pokemon storage slot: Pokemon items only, one each. */
private class PokemonSlot(container: Container, slot: Int, x: Int, y: Int) : Slot(container, slot, x, y) {
    override fun mayPlace(stack: ItemStack) = PokemonItemData.isPokemonItem(stack)
    override fun getMaxStackSize() = 1
}

/**
 * A storage window: all [PresetData.STORAGE_SIZE] slots in six rows over the player inventory, drawn with vanilla's
 * large chest screen.
 *
 * In the item storage, items leaving for the player inventory lose their mark; a storage item on the cursor keeps it,
 * so dropping it on a held-item slot lends it to that Pokemon (see the held-item exchange). The Pokemon storage takes
 * only Pokemon items; the party column beside it itemizes and restores Pokemon as in the inventory, and shift-clicking a
 * Pokemon in the storage puts it in the lowest empty party slot.
 */
class StorageMenu(val kind: StorageKind, id: Int, inventory: Inventory, private val storage: Container) :
    ChestMenu(typeOf(kind), id, inventory, storage, ROWS) {

    private val playerInventory = inventory

    init {
        if (kind == StorageKind.POKEMON) for (index in 0 until PresetData.STORAGE_SIZE) {
            val old = slots[index]
            slots[index] = PokemonSlot(storage, index, old.x, old.y).also { it.index = index }
        }
    }

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (player !is ServerPlayer) return click(slotId, button, clickType, player)
        // Every click is logged by what it changed in the storage, whatever kind of click it was.
        StorageLog.tracked(player, storage, kind, "player:${clickType.name.lowercase()}") { click(slotId, button, clickType, player) }
    }

    private fun click(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (kind == StorageKind.POKEMON) {
            super.clicked(slotId, button, clickType, player)
            return
        }
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
        // A Pokemon shift-clicked in the Pokemon storage goes back to the party, not the inventory. Cobblemon To Item
        // makes the move on the server; the client only waits for the result.
        if (kind == StorageKind.POKEMON && index < PresetData.STORAGE_SIZE) {
            if (player is ServerPlayer) PokemonTransfers.restoreFromMenuSlot(player, index)
            return ItemStack.EMPTY
        }
        if (kind == StorageKind.ITEMS && index >= PresetData.STORAGE_SIZE) StoredMark.mark(slots[index].item)
        val result = super.quickMoveStack(player, index)
        stripInventory()
        return result
    }

    override fun removed(player: Player) {
        if (kind == StorageKind.ITEMS) StoredMark.unmark(carried)
        super.removed(player)
    }

    /** Anything that reached the player inventory has been taken out of the item storage. */
    private fun stripInventory() {
        if (kind != StorageKind.ITEMS) return
        for (slot in 0 until playerInventory.containerSize) StoredMark.unmark(playerInventory.getItem(slot))
    }

    companion object {
        const val ROWS = PresetData.STORAGE_SIZE / 9

        val ITEM_TYPE: MenuType<StorageMenu> = MenuType({ id, inventory ->
            StorageMenu(StorageKind.ITEMS, id, inventory, SimpleContainer(PresetData.STORAGE_SIZE))
        }, FeatureFlags.VANILLA_SET)

        val POKEMON_TYPE: MenuType<StorageMenu> = MenuType({ id, inventory ->
            StorageMenu(StorageKind.POKEMON, id, inventory, SimpleContainer(PresetData.STORAGE_SIZE))
        }, FeatureFlags.VANILLA_SET)

        fun typeOf(kind: StorageKind) = if (kind == StorageKind.ITEMS) ITEM_TYPE else POKEMON_TYPE

        fun register() {
            Registry.register(BuiltInRegistries.MENU, CobblemonPartySidebar.id("item_storage"), ITEM_TYPE)
            Registry.register(BuiltInRegistries.MENU, CobblemonPartySidebar.id("pokemon_storage"), POKEMON_TYPE)
        }
    }
}
