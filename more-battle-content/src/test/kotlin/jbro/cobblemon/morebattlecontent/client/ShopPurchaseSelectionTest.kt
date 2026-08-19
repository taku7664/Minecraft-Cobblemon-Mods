package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopPurchaseSelectionTest {
    @Test
    fun `choosing another product replaces the purchase target instead of building a cart`() {
        val selection = ShopPurchaseSelection()

        selection.select("first")
        selection.change(2, maxQuantity = 16, itemCount = 1, maxTotalItems = 64)
        selection.select("second")

        assertEquals("second", selection.entryId)
        assertEquals(1, selection.quantity)
        assertEquals(listOf("second"), selection.lines().map { it.entryId })
    }

    @Test
    fun `quantity stays within one selected product limits`() {
        val selection = ShopPurchaseSelection()
        selection.select("item")

        assertFalse(selection.change(-1, maxQuantity = 4, itemCount = 2, maxTotalItems = 6))
        assertTrue(selection.change(1, maxQuantity = 4, itemCount = 2, maxTotalItems = 6))
        assertTrue(selection.change(1, maxQuantity = 4, itemCount = 2, maxTotalItems = 6))
        assertFalse(selection.change(1, maxQuantity = 4, itemCount = 2, maxTotalItems = 6))
        assertEquals(3, selection.quantity)
        assertEquals(6, selection.totalItems(itemCount = 2))
    }

    @Test
    fun `successful purchase keeps the selected product and resets its quantity`() {
        val selection = ShopPurchaseSelection()
        selection.select("item")
        selection.change(3, maxQuantity = 8, itemCount = 1, maxTotalItems = 8)

        selection.resetQuantity()

        assertEquals("item", selection.entryId)
        assertEquals(1, selection.quantity)
    }
}
