package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiSmoothCornersTest {
    @Test
    fun `a rounded corner fades from empty at the tip to full inside`() {
        val (outer) = UiSmoothCorners.coverage(UiSmoothCorners.Kind.ROUND, 4, listOf(0))
        assertEquals(0f, outer[0])
        assertEquals(1f, outer[3 * 4 + 3])
        // Along the arc some pixels are only partly covered: these are the in-between pixels.
        assertTrue(outer.any { it > 0f && it < 1f })
        // The corner is symmetric about its diagonal.
        for (py in 0 until 4) for (px in 0 until 4) assertEquals(outer[py * 4 + px], outer[px * 4 + py])
    }

    @Test
    fun `coverage shrinks as a frame layer moves inward`() {
        val layers = UiSmoothCorners.coverage(UiSmoothCorners.Kind.ROUND, 5, listOf(0, 1, 3, 4))
        for (index in 0 until 25) {
            for (layer in 1 until layers.size) assertTrue(layers[layer][index] <= layers[layer - 1][index])
        }
        // A layer never covers pixels outside its inset.
        assertEquals(0f, layers[2][0 * 5 + 4])
        assertEquals(0f, layers[3][4 * 5 + 2])
    }

    @Test
    fun `a chamfer cuts along its diagonal and a square corner stays full`() {
        val (cut) = UiSmoothCorners.coverage(UiSmoothCorners.Kind.CUT, 4, listOf(0))
        assertEquals(0f, cut[0])
        assertEquals(0.625f, cut[0 * 4 + 3], "a pixel the cut line crosses")
        assertEquals(1f, cut[3 * 4 + 3])
        val (square) = UiSmoothCorners.coverage(UiSmoothCorners.Kind.SQUARE, 4, listOf(0))
        assertTrue(square.all { it == 1f })
    }

    @Test
    fun `only rounded and chamfered shapes get smooth corners, sized to fit`() {
        assertEquals(4, UiSmoothCorners.cornerSize(UiShape.RoundedRectangle(4), 40, 20))
        assertEquals(3, UiSmoothCorners.cornerSize(UiShape.RoundedRectangle(8), 40, 6))
        assertEquals(2, UiSmoothCorners.cornerSize(UiShape.Chamfer(4), 40, 6))
        assertEquals(0, UiSmoothCorners.cornerSize(UiShape.Rectangle, 40, 20))
        assertEquals(UiSmoothCorners.Kind.SQUARE,
            UiSmoothCorners.kind(UiShape.Chamfer(3, setOf(UiCorner.TOP_RIGHT)), UiCorner.TOP_LEFT))
    }

    @Test
    fun `each corner is worked out once`() {
        assertSame(
            UiSmoothCorners.coverage(UiSmoothCorners.Kind.ROUND, 4, listOf(0, 1, 3, 4)),
            UiSmoothCorners.coverage(UiSmoothCorners.Kind.ROUND, 4, listOf(0, 1, 3, 4))
        )
    }
}
