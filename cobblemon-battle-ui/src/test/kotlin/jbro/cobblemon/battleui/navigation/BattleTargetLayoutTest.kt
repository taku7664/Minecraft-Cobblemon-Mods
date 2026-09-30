package jbro.cobblemon.battleui.navigation

import jbro.cobblemon.battleui.extended.ui.shared.BattleTargetLayout
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleTargetLayoutTest {
    @Test
    fun `field rows use a wide shallow panel and fill their columns`() {
        val doubles = BattleTargetLayout.calculate(427, 240, 2)
        assertEquals(248, doubles.panel.width)
        assertEquals(84, doubles.panel.height)
        assertEquals(19, doubles.opponents[0].height)
        assertEquals(doubles.allies[0], BattleTargetLayout.card(doubles.allies[0]))
        assertTrue(doubles.back.height < 16)
        assertTrue(doubles.allies.all { it.bottom <= doubles.panel.bottom })

        val triples = BattleTargetLayout.calculate(427, 240, 3)
        assertEquals(310, triples.panel.width)
        assertEquals(84, triples.panel.height)
        assertEquals(19, triples.opponents[0].height)
        assertEquals(triples.opponents[2], BattleTargetLayout.card(triples.opponents[2]))
        assertTrue(triples.opponents.zipWithNext().all { (left, right) -> left.right < right.x })
    }

    @Test
    fun `narrow viewport shares available room without changing field order`() {
        val layout = BattleTargetLayout.calculate(200, 240, 3)
        assertEquals(UiRect(10, 126, 180, 84), layout.panel)
        assertEquals(3, layout.opponents.size)
        assertTrue(layout.opponents.zipWithNext().all { (left, right) -> left.right <= right.x })
        assertTrue(layout.opponents.all { it.x >= layout.panel.x && it.right <= layout.panel.right })
        assertEquals(layout.opponents.map { it.width }, layout.allies.map { it.width })
    }
}
