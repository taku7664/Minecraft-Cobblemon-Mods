package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomePlayerModelPlacementTest {
    @Test
    fun `home model is centered fills the panel and keeps the whole body inside the viewport`() {
        val viewport = TowerPlayRect(20, 30, 136, 276)
        val placement = HomePlayerModelPlacement.calculate(viewport, entityHeight = 1.8f)
        val halfRenderedHeight = 1.8f * placement.scale / 2f

        assertEquals(viewport.left + viewport.width / 2, placement.centerX)
        assertEquals(viewport.top + viewport.height / 2, placement.centerY)
        assertTrue(placement.scale >= 100)
        assertTrue(placement.centerY - halfRenderedHeight >= viewport.top)
        assertTrue(placement.centerY + halfRenderedHeight <= viewport.bottom)
        assertTrue(halfRenderedHeight * 2f >= viewport.height * 0.65f)
    }

    @Test
    fun `narrow home still keeps the whole player model visible`() {
        val viewport = TowerPlayRect(4, 4, 56, 140)
        val placement = HomePlayerModelPlacement.calculate(viewport, entityHeight = 1.8f)
        val halfRenderedHeight = 1.8f * placement.scale / 2f

        assertTrue(placement.scale >= 36)
        assertTrue(placement.centerY - halfRenderedHeight >= viewport.top)
        assertTrue(placement.centerY + halfRenderedHeight <= viewport.bottom)
    }

    @Test
    fun `shared player renderer receives the viewport center instead of its bottom edge`() {
        assertEquals(88, PlayerModelCentering.centerY(top = 17, bottomExclusive = 160))
    }
}
