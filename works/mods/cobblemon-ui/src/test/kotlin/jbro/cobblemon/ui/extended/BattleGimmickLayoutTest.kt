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
    fun `one two and three gimmicks stay centred beside the move grid at 427 by 240`() {
        assertEquals(listOf(UiRect(189, 133, 90, 26)),
            BattleScreenGeometry.gimmickTiles(427, 240, 1))
        assertEquals(listOf(UiRect(189, 118, 90, 26), UiRect(189, 148, 90, 26)),
            BattleScreenGeometry.gimmickTiles(427, 240, 2))
        assertEquals(listOf(UiRect(189, 103, 90, 26), UiRect(189, 133, 90, 26), UiRect(189, 163, 90, 26)),
            BattleScreenGeometry.gimmickTiles(427, 240, 3))
    }

    @Test
    fun `gimmick column remains inside a smaller screen`() {
        val bounds = BattleScreenGeometry.gimmickTiles(320, 180, 3)

        assertEquals(listOf(UiRect(82, 43, 90, 26), UiRect(82, 73, 90, 26), UiRect(82, 103, 90, 26)), bounds)
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
            moves.forEach { move ->
                assertFalse(overlaps(gimmick, move))
                assertTrue(gimmick.x() + gimmick.width() <= move.x() - BattleScreenGeometry.FOCUS_PROTRUSION)
            }
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
