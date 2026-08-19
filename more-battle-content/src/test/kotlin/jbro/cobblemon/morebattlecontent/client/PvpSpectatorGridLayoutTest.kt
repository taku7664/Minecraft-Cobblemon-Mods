package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorGridLayoutTest {
    @Test
    fun `spectators fill ten vertical rows before adding a centered column`() {
        val bounds = TowerPlayRect(20, 30, 180, 124)
        val layout = PvpSpectatorGridLayout.calculate(bounds, List(11) { 42 })

        assertEquals(10, layout.rows)
        assertEquals(2, layout.columns)
        assertEquals(layout.slots[0].bounds.left, layout.slots[9].bounds.left)
        assertTrue(layout.slots[10].bounds.left > layout.slots[9].bounds.left)
        assertEquals(layout.slots[0].bounds.top, layout.slots[10].bounds.top)
        assertEquals(bounds.left + (bounds.width - layout.block.width) / 2, layout.block.left)
    }

    @Test
    fun `narrow height uses available rows while keeping face and nickname room`() {
        val bounds = TowerPlayRect(7, 11, 96, 40)
        val layout = PvpSpectatorGridLayout.calculate(bounds, listOf(30, 60, 24, 48, 36))

        assertEquals(3, layout.rows)
        assertEquals(2, layout.columns)
        assertTrue(layout.slots.all { it.face.width == 10 && it.nameWidth > 0 })
        assertTrue(layout.slots.all { bounds.contains(it.bounds) })
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
