package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueTrainerModelPlacementTest {
    @Test
    fun `model is centered inside the trainer viewport`() {
        val viewport = LeagueUiRect(left = 30, top = 40, width = 48, height = 48)

        val placement = LeagueTrainerModelPlacement.calculate(viewport, entityHeight = 1.8f)

        assertEquals(54, placement.centerX)
        assertEquals(64, placement.centerY)
        assertTrue(placement.scale > 0)
    }

    @Test
    fun `narrow viewport constrains model scale by width`() {
        val viewport = LeagueUiRect(left = 0, top = 0, width = 24, height = 60)

        val placement = LeagueTrainerModelPlacement.calculate(viewport, entityHeight = 1.8f)

        assertEquals(21, placement.scale)
    }
}
