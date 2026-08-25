package jbro.cobblemon.morebattlecontent.client

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSelectionLayoutTest {
    @Test
    fun `selection columns fill the content width symmetrically`() {
        listOf(
            TowerPlayRect(left = 8, top = 8, width = 304, height = 224),
            TowerPlayRect(left = 157, top = 82, width = 540, height = 320),
        ).forEach { shell ->
            val columns = PvpSelectionLayout.columns(
                shell = shell,
                top = shell.top + 38,
                height = shell.height - 77,
            )

            assertEquals(shell.left + 6, columns.left.left)
            assertEquals(shell.right - 6, columns.right.right)
            assertEquals(5, columns.center.left - columns.left.right)
            assertEquals(5, columns.right.left - columns.center.right)
            assertTrue(abs(columns.left.width - columns.right.width) <= 1)
        }
    }

    @Test
    fun `six cards stay inside the panel without overlapping`() {
        listOf(compactPanel(), widePanel()).forEach { panel ->
            val cards = (0 until PvpSelectionLayout.PARTY_SIZE).map { PvpSelectionLayout.partyCard(panel, it) }
            cards.forEach { card ->
                assertTrue(card.left >= panel.left, "card escapes the panel on the left")
                assertTrue(card.right <= panel.right, "card escapes the panel on the right")
                assertTrue(card.top >= panel.top, "card escapes the panel on the top")
                assertTrue(card.height > 0, "card has no height")
            }
            cards.zipWithNext().forEach { (upper, lower) ->
                assertTrue(lower.top >= upper.bottom, "cards overlap")
            }
        }
    }

    @Test
    fun `card content keeps a square portrait left of the text column`() {
        val panel = widePanel()
        (0 until PvpSelectionLayout.PARTY_SIZE).forEach { index ->
            val card = PvpSelectionLayout.partyCard(panel, index)
            val content = PvpSelectionLayout.partyCardContent(panel, index)

            assertEquals(index, content.index)
            assertEquals(content.portrait.width, content.portrait.height, "portrait is not square")
            assertTrue(content.portrait.left >= card.left, "portrait escapes the card")
            assertTrue(content.portrait.bottom <= card.bottom, "portrait escapes the card")
            assertTrue(content.textLeft > content.portrait.right, "text overlaps the portrait")
            assertTrue(content.textRight > content.textLeft, "text column has no width")
            assertTrue(content.detailsTop > content.nameTop, "details line is not below the name")
        }
    }

    @Test
    fun `the smallest supported window still produces usable cards`() {
        val panel = compactPanel()
        val content = PvpSelectionLayout.partyCardContent(panel, 0)

        assertTrue(content.portrait.width >= 14, "portrait shrinks below the readable minimum")
        assertTrue(content.textRight - content.textLeft >= 1, "text column collapses")
    }

    // Mirrors the side panels PvpSelectionScreen derives from a 320x240 and a 540-wide shell.
    private fun compactPanel() = TowerPlayRect(left = 8, top = 40, width = 96, height = 163)

    private fun widePanel() = TowerPlayRect(left = 12, top = 44, width = 204, height = 243)
}
