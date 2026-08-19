package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPlayLayoutTest {
    @Test
    fun `compact minecraft window keeps party rail and stacked play panels inside custom shell`() {
        val layout = TowerPlayLayout.calculate(MbcContentFrameLayout.calculate(320, 240).content)
        val cards = (0 until 6).map(layout::partyCard)
        val actions = layout.actionButtons(2)

        assertEquals(TowerPlayLayoutMode.COMPACT, layout.mode)
        assertTrue(layout.shell.contains(layout.partyPanel))
        assertTrue(layout.shell.contains(layout.mainPanel))
        assertTrue(layout.shell.contains(layout.detailsPanel))
        assertTrue(layout.partyPanel.right < layout.mainPanel.left)
        assertTrue(layout.mainPanel.bottom < layout.detailsPanel.top)
        assertTrue(cards.all(layout.partyPanel::contains))
        assertTrue(cards.zipWithNext().all { (upper, lower) -> upper.bottom < lower.top })
        assertTrue(actions.zipWithNext().all { (left, right) -> left.right < right.left })
        assertTrue(actions.all(layout.detailsPanel::contains))
    }

    @Test
    fun `wide window uses party main and details columns inside custom shell`() {
        val layout = TowerPlayLayout.calculate(MbcContentFrameLayout.calculate(854, 480).content)
        val cards = (0 until 6).map(layout::partyCard)

        assertEquals(TowerPlayLayoutMode.WIDE, layout.mode)
        assertTrue(layout.partyPanel.right < layout.mainPanel.left)
        assertTrue(layout.mainPanel.right < layout.detailsPanel.left)
        assertEquals(layout.partyPanel.top, layout.mainPanel.top)
        assertEquals(layout.mainPanel.top, layout.detailsPanel.top)
        assertEquals(layout.partyPanel.bottom, layout.detailsPanel.bottom)
        assertTrue(cards.all(layout.partyPanel::contains))
        assertEquals(layout.detailsPanel.left + 5, layout.actionButtons(2).first().left)
        assertEquals(layout.detailsPanel.right - 5, layout.actionButtons(2).last().right)
    }

    @Test
    fun `custom shell provides clipped portrait text and progress geometry`() {
        val layout = TowerPlayLayout.calculate(MbcContentFrameLayout.calculate(854, 480).content)
        val cards = (0 until 6).map(layout::partyCard)
        val contents = (0 until 6).map(layout::partyCardContent)
        val progress = layout.progressSegments(10)

        assertTrue(contents.all { it.portrait.width == it.portrait.height })
        assertTrue(contents.all { cards[it.index].contains(it.portrait) })
        assertTrue(contents.all { it.textLeft > it.portrait.right && it.textRight <= cards[it.index].right })
        assertTrue(contents.all { it.detailsTop > it.nameTop && it.detailsTop < cards[it.index].bottom })
        assertEquals(10, progress.size)
        assertTrue(progress.all(layout.mainPanel::contains))
        assertTrue(progress.zipWithNext().all { (left, right) -> left.right < right.left })
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
