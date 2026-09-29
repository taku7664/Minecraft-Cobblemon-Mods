package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCartLine
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopLimits
import jbro.cobblemon.mcc.internal.bp.shop.ShopEntryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopCartTest {
    private val band = ShopEntryView("choice_band", "cobblemon:choice_band", 1, 25L)
    private val orb = ShopEntryView("life_orb", "cobblemon:life_orb", 1, 40L)
    private val berries = ShopEntryView("sitrus", "cobblemon:sitrus_berry", 4, 10L)
    private val entries = listOf(band, orb, berries)

    @Test
    fun `pressing an item again raises its line and the totals follow`() {
        val cart = ShopCart()
        val limits = BattlePointShopLimits(16, 64, 64)
        assertTrue(cart.add(band, limits, entries))
        assertTrue(cart.add(band, limits, entries))
        assertTrue(cart.add(berries, limits, entries))

        assertEquals(listOf(BattlePointShopCartLine("choice_band", 2), BattlePointShopCartLine("sitrus", 1)), cart.lines())
        assertEquals(6, cart.totalItems(entries))
        assertEquals(60L, cart.totalCost(entries))
    }

    @Test
    fun `removing steps a line down and drops it at zero`() {
        val cart = ShopCart()
        val limits = BattlePointShopLimits(16, 64, 64)
        cart.add(band, limits, entries)
        cart.add(band, limits, entries)
        assertTrue(cart.remove("choice_band"))
        assertEquals(1, cart.quantity("choice_band"))
        assertTrue(cart.remove("choice_band"))
        assertTrue(cart.isEmpty)
        assertFalse(cart.remove("choice_band"))
    }

    @Test
    fun `the cart keeps to the line quantity and total item limits`() {
        val cart = ShopCart()
        val lines = BattlePointShopLimits(1, 64, 64)
        assertTrue(cart.add(band, lines, entries))
        assertFalse(cart.add(orb, lines, entries), "a second line is over the line limit")
        assertTrue(cart.add(band, lines, entries), "the existing line can still grow")

        val quantity = ShopCart()
        val perLine = BattlePointShopLimits(16, 2, 64)
        repeat(2) { quantity.add(orb, perLine, entries) }
        assertFalse(quantity.add(orb, perLine, entries))

        val items = ShopCart()
        val total = BattlePointShopLimits(16, 64, 6)
        assertTrue(items.add(berries, total, entries))
        assertFalse(items.add(berries, total, entries), "eight items are over six")
        assertTrue(items.add(band, total, entries))
    }

    @Test
    fun `lines the catalog no longer sells are dropped`() {
        val cart = ShopCart()
        val limits = BattlePointShopLimits(16, 64, 64)
        cart.add(band, limits, entries)
        cart.add(orb, limits, entries)
        cart.retain(listOf(orb))
        assertEquals(listOf(BattlePointShopCartLine("life_orb", 1)), cart.lines())
    }
}
