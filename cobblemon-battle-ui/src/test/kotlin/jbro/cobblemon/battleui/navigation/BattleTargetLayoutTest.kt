package jbro.cobblemon.battleui.navigation

import jbro.cobblemon.battleui.extended.ui.shared.BattleTargetLayout
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleTargetLayoutTest {
    @Test
    fun `field rows retain the reviewed compact geometry`() {
        val doubles = BattleTargetLayout.calculate(427, 240, 2)
        assertEquals(UiRect(123, 114, 180, 96), doubles.panel)
        assertEquals(UiRect(133, 150, 75, 20), doubles.opponents[0])
        assertEquals(UiRect(218, 185, 75, 20), doubles.allies[1])
        assertEquals(UiRect(253, 120, 40, 16), doubles.back)
        assertEquals(70, BattleTargetLayout.card(doubles.allies[0]).width)

        val triples = BattleTargetLayout.calculate(427, 240, 3)
        assertEquals(UiRect(90, 114, 246, 96), triples.panel)
        assertEquals(UiRect(100, 150, 70, 18), triples.opponents[0])
        assertEquals(UiRect(256, 185, 70, 18), triples.allies[2])
    }

    @Test
    fun `narrow viewport shares available room without changing field order`() {
        val layout = BattleTargetLayout.calculate(200, 240, 3)
        assertEquals(UiRect(10, 114, 180, 96), layout.panel)
        assertEquals(3, layout.opponents.size)
        assertTrue(layout.opponents.zipWithNext().all { (left, right) -> left.right <= right.x })
        assertTrue(layout.opponents.all { it.x >= layout.panel.x && it.right <= layout.panel.right })
        assertEquals(layout.opponents.map { it.width }, layout.allies.map { it.width })
    }
}
