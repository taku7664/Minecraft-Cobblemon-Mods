package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleGimmickIcons
import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import jbro.cobblemon.ui.navigation.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleGimmickLayoutTest {
    @Test
    fun `round buttons stack from the move grid's bottom up at 427 by 240`() {
        // 1280x720 at GUI scale 3: the moves end at y=216; the first button Cobblemon offers is the bottom one.
        assertEquals(listOf(UiRect(242, 190, 26, 26)), BattleScreenGeometry.gimmickTiles(427, 240, 1))
        assertEquals(listOf(UiRect(242, 190, 26, 26), UiRect(242, 160, 26, 26), UiRect(242, 130, 26, 26)),
            BattleScreenGeometry.gimmickTiles(427, 240, 3))
        val moves = BattleScreenGeometry.moveTiles(427, 240, 4)
        assertEquals(moves.last().y() + moves.last().height(), BattleScreenGeometry.gimmickTiles(427, 240, 2).first().let { it.y() + it.height() })
    }

    @Test
    fun `the buttons stay clear of a focused move's slide and cursor arrow`() {
        for ((width, height) in listOf(427 to 240, 480 to 270, 640 to 360)) {
            val moveLeft = BattleScreenGeometry.moveTiles(width, height, 4).first().x()
            BattleScreenGeometry.gimmickTiles(width, height, 3).forEach {
                // The slide overshoots FOCUS_PROTRUSION a little; the arrow's tip is 3 px further left and it is 6 wide.
                assertTrue(it.x() + it.width() <= moveLeft - BattleScreenGeometry.FOCUS_PROTRUSION - 10, "at $width")
            }
        }
    }

    @Test
    fun `the move tooltip rises above the buttons`() {
        for ((width, height) in listOf(427 to 240, 480 to 270, 640 to 360)) {
            for (count in 1..2) {
                val buttons = BattleScreenGeometry.gimmickTiles(width, height, count)
                val moves = BattleScreenGeometry.moveTiles(width, height, 4)
                // As the renderer registers them: the moves from their tiles' focus-protruded left edge.
                val anchor = MoveTooltipAnchor.position(moves.first().x() - BattleScreenGeometry.FOCUS_PROTRUSION,
                    buttons.last().y(), moves.last().y() + moves.last().height(), TOOLTIP, TOOLTIP_HEIGHT, width, height)
                assertTrue(anchor.y() >= MoveTooltipAnchor.SCREEN_MARGIN, "tooltip off screen at $width with $count")
                assertTrue(anchor.y() + TOOLTIP_HEIGHT <= buttons.last().y() - MoveTooltipAnchor.GIMMICK_CLEARANCE,
                    "tooltip over the buttons at $width with $count")
            }
        }
    }

    @Test
    fun `gimmick column remains inside a smaller screen`() {
        val bounds = BattleScreenGeometry.gimmickTiles(320, 180, 3)

        bounds.forEach {
            assertTrue(it.x() >= 0 && it.y() >= 0)
            assertTrue(it.x() + it.width() <= 320)
            assertTrue(it.y() + it.height() <= 180)
        }
    }

    @Test
    fun `gimmicks do not overlap moves or the back button at minimum supported layout`() {
        val gimmicks = BattleScreenGeometry.gimmickTiles(427, 240, 3)
        val moves = BattleScreenGeometry.moveTiles(427, 240, 4)
        val back = UiRect(9, 218, 29, 17)

        gimmicks.forEach { gimmick ->
            moves.forEach { move -> assertFalse(overlaps(gimmick, move)) }
            assertFalse(overlaps(gimmick, back))
        }
    }

    @Test
    fun `a symbol is lifted off its button face, cropped and transparent around it`() {
        val glyph = BattleGimmickIcons.extract(button(offSymbol = WHITE, onSymbol = DARK))!!

        assertEquals(3, glyph.width)
        assertEquals(4, glyph.height)
        assertEquals(WHITE, glyph.pixels[0])
        assertEquals(SHADOW, glyph.pixels[3 * 3 + 2])
        assertEquals(0, glyph.pixels[3 * 3])
    }

    @Test
    fun `the brighter state's symbol is the one kept, so a black off symbol still reads`() {
        val glyph = BattleGimmickIcons.extract(button(offSymbol = BLACK, onSymbol = YELLOW))!!

        assertEquals(YELLOW, glyph.pixels[0])
    }

    @Test
    fun `a plain button has no symbol`() {
        assertNull(BattleGimmickIcons.extract(IntArray(36 * 68) { if (it / 36 % 34 < 2) LIGHT else FACE }))
    }

    /**
     * A 36x68 button: a light band on top and a face elsewhere in both halves, a 3x3 symbol at (20, 10) with a shadow
     * pixel under its right end.
     */
    private fun button(offSymbol: Int, onSymbol: Int): IntArray = IntArray(36 * 68) { index ->
        val x = index % 36
        val y = index / 36 % 34
        val on = index / 36 >= 34
        when {
            x in 20..22 && y in 10..12 -> if (on) onSymbol else offSymbol
            x == 22 && y == 13 -> SHADOW
            y < 2 -> LIGHT
            else -> if (on) ON_FACE else FACE
        }
    }

    private companion object {
        /** The move tooltip's width at the default font scale: 210 at a text size rounded from 0.85 up to 1. */
        const val TOOLTIP = 247
        /** A move tooltip with a two-line description and one effectiveness line. */
        const val TOOLTIP_HEIGHT = 150
        // ABGR, as NativeImage holds pixels.
        const val FACE = 0xFF676767.toInt()
        const val LIGHT = 0xFFC6C6C6.toInt()
        const val ON_FACE = 0xFF5A1E9C.toInt()
        const val SHADOW = 0xFF3E3E3E.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val DARK = 0xFF202020.toInt()
        const val YELLOW = 0xFF7FFFFF.toInt()
    }

    private fun overlaps(a: UiRect, b: UiRect): Boolean =
        a.x() < b.x() + b.width() && a.x() + a.width() > b.x() &&
            a.y() < b.y() + b.height() && a.y() + a.height() > b.y()
}
