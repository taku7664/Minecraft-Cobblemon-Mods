package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccHubPortraitCardsTest {
    /** Card bodies from the smallest hub content area up to the largest. */
    private val bodies = listOf(UiRect(116, 90, 163, 101), UiRect(0, 0, 229, 177), UiRect(40, 20, 328, 257), UiRect(0, 0, 560, 120))

    @Test
    fun `cards stay inside the body with square portraits and never overlap`() {
        bodies.forEach { body ->
            listOf(3, 4, 6).forEach { count ->
                val cells = MccHubPortraitCards.grid(body, count)
                assertEquals(count, cells.size, "$body x$count")
                cells.forEach { cell ->
                    assertTrue(body.contains(cell.bounds), "$body ${cell.bounds}")
                    assertTrue(cell.bounds.contains(cell.portrait), "$body portrait")
                    assertTrue(cell.bounds.contains(cell.text), "$body text")
                    assertEquals(cell.portrait.width, cell.portrait.height)
                }
                cells.forEachIndexed { index, cell ->
                    cells.drop(index + 1).forEach { other -> assertFalse(cell.bounds.overlaps(other.bounds), "$body x$count") }
                }
            }
        }
    }

    @Test
    fun `a small party card puts portraits beside the text and a large one above it`() {
        val small = MccHubPortraitCards.grid(bodies[0], 6)
        val large = MccHubPortraitCards.grid(bodies[2], 6)
        assertTrue(small.none { it.stacked })
        assertTrue(large.all { it.stacked })
        assertTrue(large.first().portrait.width > small.first().portrait.width)
    }

    @Test
    fun `a wide short body lays its cards out in one row`() {
        val cells = MccHubPortraitCards.grid(bodies[3], 3)
        assertEquals(1, cells.map { it.bounds.y }.distinct().size)
    }

    @Test
    fun `nothing is laid out when no card can fit`() {
        assertTrue(MccHubPortraitCards.grid(UiRect(0, 0, 40, 20), 6).isEmpty())
        assertTrue(MccHubPortraitCards.grid(bodies[0], 0).isEmpty())
    }
}

private fun UiRect.contains(other: UiRect): Boolean =
    other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

private fun UiRect.overlaps(other: UiRect): Boolean =
    x < other.right && other.x < right && y < other.bottom && other.y < bottom
