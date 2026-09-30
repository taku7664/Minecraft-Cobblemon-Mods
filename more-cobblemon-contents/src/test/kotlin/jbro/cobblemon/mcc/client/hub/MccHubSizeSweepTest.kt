package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.client.MccShopLayout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Screens are resized freely; no hub size may make a layout throw and crash the game. */
class MccHubSizeSweepTest {
    /** Every hub content area from the smallest screen the hub lays tabs out on up to a large one. */
    private val contentAreas = buildList {
        for (width in MccHubLayout.MIN_WIDTH..900 step 23) for (height in MccHubLayout.MIN_HEIGHT..500 step 17) {
            add(MccHubLayout.calculate(width, height, 7).content)
        }
    }

    @Test
    fun `the hub chrome fits every screen it lays tabs out on`() {
        for (width in MccHubLayout.MIN_WIDTH..900 step 23) for (height in MccHubLayout.MIN_HEIGHT..500 step 17) {
            val layout = MccHubLayout.calculate(width, height, 7)
            for (rect in listOf(layout.shell, layout.header, layout.rail, layout.content, layout.balance, layout.closeButton)) {
                assertTrue(rect.x >= 0 && rect.y >= 0 && rect.right <= width && rect.bottom <= height, "$rect in ${width}x$height")
            }
            assertTrue(layout.content.width > 0 && layout.content.height > 0, "${width}x$height")
            assertTrue(layout.tabButtons().all { it.bottom <= layout.rail.bottom }, "${width}x$height")
        }
    }

    @Test
    fun `smaller screens are refused rather than laid out`() {
        assertFalse(MccHubLayout.fits(253, 480))
        assertFalse(MccHubLayout.fits(480, 180))
        assertTrue(MccHubLayout.fits(MccHubLayout.MIN_WIDTH, MccHubLayout.MIN_HEIGHT))
    }

    @Test
    fun `the dashboard and the shop lay out in every content area`() {
        contentAreas.forEach { area ->
            MccDashboardLayout.calculate(area)
            MccShopLayout.calculate(area)
        }
    }
}
