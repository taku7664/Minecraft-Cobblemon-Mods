package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramAppearanceTest {
    @Test
    fun `hologram opacity replaces vanilla invisibility alpha instead of multiplying it twice`() {
        val opacity = 0.62F

        assertEquals(158, ShadowHologramAppearance.alpha(38, opacity))
        assertEquals(
            ShadowHologramAppearance.alpha(255, opacity),
            ShadowHologramAppearance.alpha(38, opacity),
        )
    }

    @Test
    fun `dedicated shader only accepts complete entity vertices`() {
        assertTrue(ShadowHologramShader.supports(DefaultVertexFormat.NEW_ENTITY))
        assertFalse(ShadowHologramShader.supports(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP))
        assertFalse(ShadowHologramShader.supports(DefaultVertexFormat.POSITION_COLOR))
    }

    @Test
    fun `fallback and name tag tint matches the model blackened yellow`() {
        assertEquals(0.38F, ShadowHologramAppearance.FALLBACK_RED)
        assertEquals(0.31F, ShadowHologramAppearance.FALLBACK_GREEN)
        assertEquals(0.075F, ShadowHologramAppearance.FALLBACK_BLUE)
    }
}
