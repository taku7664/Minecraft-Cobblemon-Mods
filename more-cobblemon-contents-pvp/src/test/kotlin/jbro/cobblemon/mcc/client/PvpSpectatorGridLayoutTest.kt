package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorGridLayoutTest {
    @Test
    fun `spectators fill ten vertical rows before adding a centered column`() {
        val bounds = UiRect(20, 30, 180, 124)
        val layout = PvpSpectatorGridLayout.calculate(bounds, List(11) { 42 })

        assertEquals(10, layout.rows)
        assertEquals(2, layout.columns)
        assertEquals(layout.slots[0].bounds.x, layout.slots[9].bounds.x)
        assertTrue(layout.slots[10].bounds.x > layout.slots[9].bounds.x)
        assertEquals(layout.slots[0].bounds.y, layout.slots[10].bounds.y)
        assertEquals(bounds.x + (bounds.width - layout.block.width) / 2, layout.block.x)
    }

    @Test
    fun `narrow height uses available rows while keeping face and nickname room`() {
        val bounds = UiRect(7, 11, 96, 40)
        val layout = PvpSpectatorGridLayout.calculate(bounds, listOf(30, 60, 24, 48, 36))

        assertEquals(3, layout.rows)
        assertEquals(2, layout.columns)
        assertTrue(layout.slots.all { it.face.width == 10 && it.nameWidth > 0 })
        assertTrue(layout.slots.all { bounds.contains(it.bounds) })
    }
}

private fun UiRect.contains(other: UiRect): Boolean =
    other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom
