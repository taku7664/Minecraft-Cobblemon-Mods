package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccShopLayoutTest {
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `the counter runs left to right inside the bounds at every size`() {
        sizes.forEach { bounds ->
            val layout = MccShopLayout.calculate(bounds)
            val row = listOfNotNull(layout.keeper, layout.catalog, layout.arrow, layout.cart, layout.viewer)
            assertEquals(bounds.x, row.first().x, "$bounds")
            assertEquals(bounds.right, row.last().right, "$bounds")
            row.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.x, "$bounds") }
            assertTrue(row.all { it.y == bounds.y && it.height == bounds.height }, "$bounds")
            assertTrue(layout.catalog.width >= 100 && layout.cart.width >= 100, "$bounds lists")
        }
    }

    @Test
    fun `models stand on either side only when the hub is wide enough`() {
        val narrow = MccShopLayout.calculate(sizes[0])
        assertNull(narrow.keeper)
        assertNull(narrow.viewer)
        val wide = MccShopLayout.calculate(sizes[2])
        assertNotNull(wide.keeper)
        assertEquals(wide.keeper!!.width, wide.viewer!!.width)
    }
}
