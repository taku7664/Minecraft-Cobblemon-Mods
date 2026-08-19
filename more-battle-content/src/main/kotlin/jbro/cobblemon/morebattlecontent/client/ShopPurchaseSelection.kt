package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCartLine

internal class ShopPurchaseSelection {
    var entryId: String? = null
        private set

    var quantity: Int = 0
        private set

    fun select(nextEntryId: String) {
        if (entryId != nextEntryId || quantity < 1) {
            entryId = nextEntryId
            quantity = 1
        }
    }

    fun change(delta: Int, maxQuantity: Int, itemCount: Int, maxTotalItems: Int): Boolean {
        if (entryId == null || delta == 0) return false
        val next = quantity + delta
        if (next !in 1..maxQuantity) return false
        if (next.toLong() * itemCount > maxTotalItems.toLong()) return false
        quantity = next
        return true
    }

    fun reset() {
        entryId = null
        quantity = 0
    }

    fun resetQuantity() {
        if (entryId != null) quantity = 1
    }

    fun retain(validEntryIds: Set<String>) {
        if (entryId !in validEntryIds) reset()
    }

    fun lines(): List<BattlePointShopCartLine> =
        entryId?.takeIf { quantity > 0 }?.let { listOf(BattlePointShopCartLine(it, quantity)) }.orEmpty()

    fun totalItems(itemCount: Int): Int = itemCount * quantity

    fun totalCost(priceBp: Long): Long = priceBp * quantity.toLong()
}
