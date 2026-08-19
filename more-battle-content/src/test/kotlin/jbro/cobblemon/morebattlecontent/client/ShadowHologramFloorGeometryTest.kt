package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramFloorGeometryTest {
    @Test
    fun `floor disc is a closed radial gradient with a transparent rim`() {
        val vertices = ShadowHologramFloorGeometry.vertices()
        val center = vertices.first()
        val rim = vertices.drop(1)

        assertEquals(ShadowHologramFloorGeometry.SEGMENTS + 2, vertices.size)
        assertEquals(0F, center.x)
        assertEquals(0F, center.z)
        assertTrue(center.alpha > 0)
        assertTrue(rim.all { it.alpha == 0 })
        assertEquals(rim.first().x, rim.last().x, 0.0001F)
        assertEquals(rim.first().z, rim.last().z, 0.0001F)
        assertEquals(97, ShadowHologramFloorGeometry.COLOR_RED)
        assertEquals(79, ShadowHologramFloorGeometry.COLOR_GREEN)
        assertEquals(19, ShadowHologramFloorGeometry.COLOR_BLUE)
    }
}
