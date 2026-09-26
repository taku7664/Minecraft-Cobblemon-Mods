package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiSurfaceStyleTest {
    @Test
    fun `chamfer exposes diagonal spans and selective corners`() {
        val allCorners = UiShape.Chamfer(cutPixels = 3)

        assertEquals(UiHorizontalSpan(3, 9), allCorners.horizontalSpan(width = 12, height = 8, row = 0))
        assertEquals(UiHorizontalSpan(2, 10), allCorners.horizontalSpan(width = 12, height = 8, row = 1))
        assertEquals(UiHorizontalSpan(1, 11), allCorners.horizontalSpan(width = 12, height = 8, row = 2))
        assertEquals(UiHorizontalSpan(0, 12), allCorners.horizontalSpan(width = 12, height = 8, row = 3))
        assertEquals(UiHorizontalSpan(3, 9), allCorners.horizontalSpan(width = 12, height = 8, row = 7))

        val rightOnly = UiShape.Chamfer(
            cutPixels = 3,
            corners = setOf(UiCorner.TOP_RIGHT, UiCorner.BOTTOM_RIGHT)
        )
        assertEquals(UiHorizontalSpan(0, 9), rightOnly.horizontalSpan(width = 12, height = 8, row = 0))
        assertEquals(UiHorizontalSpan(0, 12), UiShape.Rectangle.horizontalSpan(12, 8, 0))
    }

    @Test
    fun `surface overrides can remove border and adjust opacity`() {
        val base = UiSurfaceStyle(
            shape = UiShape.Rectangle,
            fill = UiFill.Solid(0xFF102030.toInt()),
            border = UiBorder.Solid(0xFFFFFFFF.toInt(), width = 2)
        )

        val resolved = base.resolve(
            UiSurfaceOverrides(
                shape = UiShape.Chamfer(4),
                border = UiBorder.None,
                backgroundOpacity = 0.45f
            )
        )

        assertEquals(UiShape.Chamfer(4), resolved.shape)
        assertEquals(base.fill, resolved.fill)
        assertEquals(UiBorder.None, resolved.border)
        assertEquals(0.45f, resolved.backgroundOpacity)
    }

    @Test
    fun `pixel frame keeps its default shadow close to the widget`() {
        val frame = UiBorder.PixelFrame(
            outerColor = 0xFF111111.toInt(),
            highlightColor = 0xFFFFFFFF.toInt(),
            shadeColor = 0xFF777777.toInt(),
            shadowColor = 0xFF000000.toInt()
        )

        assertEquals(1, frame.shadowOffset)
    }

    @Test
    fun `invalid surface values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UiShape.Chamfer(0) }
        assertThrows(IllegalArgumentException::class.java) { UiShape.Chamfer(2, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { UiBorder.Solid(0xFFFFFFFF.toInt(), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            UiSurfaceStyle(UiShape.Rectangle, UiFill.None, UiBorder.None, 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiSurfaceOverrides(backgroundOpacity = -0.1f)
        }
    }

    @Test
    fun `chamfer copies the selected corner set`() {
        val mutableCorners = mutableSetOf(UiCorner.TOP_LEFT)
        val shape = UiShape.Chamfer(2, mutableCorners)

        mutableCorners += UiCorner.BOTTOM_RIGHT

        assertEquals(setOf(UiCorner.TOP_LEFT), shape.corners)
    }

    @Test
    fun `default theme keeps surfaces visually distinct`() {
        val theme = CobblemonUiDefaultTheme.snapshot
        val shell = theme.surfaces.shell
        val primary = theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL).surface
        val secondary = theme.style(UiButtonVariant.SECONDARY, UiWidgetState.NORMAL).surface
        val ghost = theme.style(UiButtonVariant.GHOST, UiWidgetState.NORMAL).surface

        assertTrue(shell.shape is UiShape.Chamfer)
        assertEquals(2, (shell.border as UiBorder.Solid).width)
        assertTrue(primary.shape is UiShape.Chamfer)
        assertTrue(primary.fill is UiFill.VerticalGradient)
        assertEquals(UiBorder.None, ghost.border)
        assertNotEquals(primary.shape, secondary.shape)
        assertNotEquals(primary.fill, secondary.fill)
    }
}
