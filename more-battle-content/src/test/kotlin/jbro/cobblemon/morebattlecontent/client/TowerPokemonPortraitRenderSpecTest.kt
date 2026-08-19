package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPokemonPortraitRenderSpecTest {
    @Test
    fun `portrait uses a visible card scale and Cobblemon profile origin`() {
        val bounds = TowerPlayRect(left = 20, top = 30, width = 18, height = 18)
        val pose = TowerPokemonPortraitRenderSpec.forBounds(bounds)

        assertTrue(pose.applyProfileTransform)
        assertEquals(28.0, pose.anchorY)
        assertEquals(29.0, pose.anchorX)
        assertEquals(0.0, pose.depth)
        assertTrue(pose.scale >= 12.0f)
    }
}
