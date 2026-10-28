package jbro.cobblemon.mcc.pwt.client

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PwtBracketLayoutTest {
    /** The bracket card body in the smallest hub (305x184 content) and in a roomy one. */
    private val bodies = listOf(UiRect(6, 49, 293, 100), UiRect(10, 60, 600, 260))

    @Test
    fun `fits every hub size with no overlapping boxes`() {
        bodies.forEach { body ->
            val layout = checkNotNull(PwtBracketLayout.calculate(body, emptyList())) { "no layout for $body" }
            assertEquals(15, layout.boxes.size)
            layout.boxes.forEach { box ->
                assertTrue(box.rect.x >= body.x && box.rect.right <= body.right && box.rect.y >= body.y && box.rect.bottom <= body.bottom, "$box in $body")
            }
            layout.boxes.forEachIndexed { i, a ->
                layout.boxes.drop(i + 1).forEach { b -> assertFalse(a.rect.overlaps(b.rect), "$a overlaps $b") }
            }
            assertTrue(layout.trophy.y >= body.y)
        }
    }

    @Test
    fun `winners fill the later boxes and light their paths`() {
        val winners = listOf(listOf(0, 3, 4, 7), listOf(3, 4), listOf(3))
        val layout = PwtBracketLayout.calculate(bodies[0], winners)!!
        assertEquals(listOf(0, 3, 4, 7), layout.boxes.filter { it.round == 1 }.map { it.slot })
        assertEquals(listOf(3, 4), layout.boxes.filter { it.round == 2 }.map { it.slot })
        assertEquals(3, layout.boxes.single { it.round == 3 }.slot)
        // Slot 3 goes all the way: three lit lines; slots 0, 4 and 7 one or two each; the rest dark.
        assertEquals(4 + 2 + 1, layout.lines.count { it.lit })
    }

    @Test
    fun `undecided rounds have empty boxes`() {
        val layout = PwtBracketLayout.calculate(bodies[1], listOf(listOf(1, 2, 5, 6)))!!
        assertTrue(layout.boxes.filter { it.round >= 2 }.all { it.slot == null })
        assertTrue(layout.lines.none { it.lit && it.segments.isEmpty() })
    }

    private fun UiRect.overlaps(other: UiRect) = x < other.right && other.x < right && y < other.bottom && other.y < bottom
}
