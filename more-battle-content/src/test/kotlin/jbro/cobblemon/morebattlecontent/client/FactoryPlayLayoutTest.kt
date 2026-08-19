package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryPlayLayoutTest {
    @Test
    fun `compact window keeps six rental cards and four actions inside the custom shell`() {
        val layout = FactoryPlayLayout.calculate(MbcContentFrameLayout.calculate(320, 240).content)
        val cards = layout.mainCards(6)
        val actions = layout.actionButtons(4)

        assertEquals(FactoryPlayLayoutMode.COMPACT, layout.mode)
        assertTrue(layout.shell.contains(layout.summary))
        assertTrue(layout.shell.contains(layout.content))
        assertTrue(layout.shell.contains(layout.footer))
        assertTrue(cards.all(layout.content::contains))
        assertTrue(cards.all { it.width > 0 && it.height >= 20 })
        assertTrue(actions.all(layout.footer::contains))
        assertTrue(actions.zipWithNext().all { (left, right) -> left.right < right.left })
    }

    @Test
    fun `wide window uses three rental columns and separate swap groups`() {
        val layout = FactoryPlayLayout.calculate(MbcContentFrameLayout.calculate(854, 480).content)
        val cards = layout.mainCards(6)
        val contents = cards.map(FactoryRentalCardContentLayout::calculate)
        val (teamCards, offerCards) = layout.swapCards(teamCount = 4, offerCount = 4)

        assertEquals(FactoryPlayLayoutMode.WIDE, layout.mode)
        assertEquals(cards[0].top, cards[1].top)
        assertEquals(cards[1].top, cards[2].top)
        assertTrue(cards[2].right <= layout.content.right)
        assertTrue(teamCards.all(layout.swapTeamArea::contains))
        assertTrue(offerCards.all(layout.swapOfferArea::contains))
        assertTrue(layout.swapTeamArea.right < layout.swapOfferArea.left)
        assertTrue(contents.all { it.portrait.width == it.portrait.height })
        assertTrue(contents.zip(cards).all { (content, card) -> card.contains(content.portrait) })
        assertTrue(contents.all { it.textLeft > it.portrait.right && it.textRight > it.textLeft })
    }

    @Test
    fun `compact rental cards preserve a visible portrait and text lane`() {
        val cards = FactoryPlayLayout.calculate(MbcContentFrameLayout.calculate(320, 240).content).mainCards(6)
        val contents = cards.map(FactoryRentalCardContentLayout::calculate)

        assertTrue(contents.all { it.portrait.width >= 14 })
        assertTrue(contents.all { it.textRight - it.textLeft >= 24 })
        assertTrue(contents.zip(cards).all { (content, card) -> card.contains(content.portrait) })
    }

    @Test
    fun `factory layout regions never overlap in minimum supported window`() {
        val layout = FactoryPlayLayout.calculate(MbcContentFrameLayout.calculate(300, 220).content)

        assertTrue(layout.summary.bottom < layout.content.top)
        assertTrue(layout.content.bottom < layout.footer.top)
        assertEquals(layout.shell.right, layout.footer.right)
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
