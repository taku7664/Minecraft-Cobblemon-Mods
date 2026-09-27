package jbro.cobblemon.mcc.league.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueDashboardLayoutTest {
    @Test fun `development world scale two keeps header route cards and footer distinct`() {
        val layout = LeagueDashboardLayout.calculate(427, 240)
        assertEquals(415, layout.shell.width)
        assertTrue(layout.header.bottom <= layout.route.top)
        assertTrue(layout.route.bottom <= layout.detail.top)
        assertTrue(layout.detail.right < layout.status.left)
        assertTrue(layout.detail.bottom <= layout.footer.top)
        assertTrue(layout.status.bottom <= layout.footer.top)
        assertTrue(layout.face.right < layout.ball.left)
        assertTrue(layout.ball.right < layout.stats.left)
    }

    @Test fun `larger window remains centered without stretching the dashboard indefinitely`() {
        val layout = LeagueDashboardLayout.calculate(854, 480)
        assertTrue(layout.shell.width <= 720)
        assertTrue(layout.shell.height <= 400)
        assertEquals((854 - layout.shell.width) / 2, layout.shell.left)
        assertEquals((480 - layout.shell.height) / 2, layout.shell.top)
    }
}
