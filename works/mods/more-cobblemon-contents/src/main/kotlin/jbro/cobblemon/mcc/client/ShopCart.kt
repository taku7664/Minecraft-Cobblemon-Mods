package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCartLine
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopLimits
import jbro.cobblemon.mcc.internal.bp.shop.ShopEntryView

/**
 * The lines the viewer has picked, in the order they were first added. It keeps to the catalog's limits so a
 * purchase the server would reject for size is never sent; prices and the balance stay the server's.
 */
internal class ShopCart {
    private val quantities = LinkedHashMap<String, Int>()

    val isEmpty: Boolean get() = quantities.isEmpty()

    fun quantity(entryId: String): Int = quantities[entryId] ?: 0

    /** Whether one more of [entry] fits: a new line, a line's quantity and the total item count all have limits. */
    fun canAdd(entry: ShopEntryView, limits: BattlePointShopLimits, entries: List<ShopEntryView>): Boolean {
        val current = quantity(entry.entryId)
        if (current == 0 && quantities.size >= limits.maxCartLines) return false
        if (current >= limits.maxQuantityPerLine) return false
        return totalItems(entries) + entry.itemCount <= limits.maxTotalItems
    }

    fun add(entry: ShopEntryView, limits: BattlePointShopLimits, entries: List<ShopEntryView>): Boolean {
        if (!canAdd(entry, limits, entries)) return false
        quantities[entry.entryId] = quantity(entry.entryId) + 1
        return true
    }

    /** Takes one of [entryId] out; the line goes once it reaches zero. */
    fun remove(entryId: String): Boolean {
        val current = quantities[entryId] ?: return false
        if (current <= 1) quantities.remove(entryId) else quantities[entryId] = current - 1
        return true
    }

    fun clear() = quantities.clear()

    /** Drops lines the catalog no longer sells. */
    fun retain(entries: List<ShopEntryView>) {
        val sold = entries.mapTo(HashSet(), ShopEntryView::entryId)
        quantities.keys.retainAll(sold)
    }

    /** The picked entries with their quantities, in cart order, skipping any the catalog no longer has. */
    fun picked(entries: List<ShopEntryView>): List<Pair<ShopEntryView, Int>> {
        val byId = entries.associateBy(ShopEntryView::entryId)
        return quantities.mapNotNull { (id, quantity) -> byId[id]?.let { it to quantity } }
    }

    fun lines(): List<BattlePointShopCartLine> = quantities.map { (id, quantity) -> BattlePointShopCartLine(id, quantity) }

    fun totalItems(entries: List<ShopEntryView>): Int = picked(entries).sumOf { (entry, quantity) -> entry.itemCount * quantity }

    fun totalCost(entries: List<ShopEntryView>): Long = picked(entries).sumOf { (entry, quantity) -> entry.priceBp * quantity }
}
