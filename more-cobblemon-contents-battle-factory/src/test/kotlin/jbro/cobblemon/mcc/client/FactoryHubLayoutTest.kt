package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryHubLayoutTest {
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `every hub size keeps strip body and footer inside the bounds without overlap`() {
        sizes.forEach { bounds ->
            val layout = FactoryHubLayout.calculate(bounds)
            assertTrue(layout.strip.bottom <= layout.body.y, "$bounds")
            assertTrue(layout.body.bottom <= layout.footer.y, "$bounds")
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
        }
    }

    @Test
    fun `a draft of six and a double team of four fit under the instruction line`() {
        sizes.forEach { bounds ->
            val body = MccHubKit.cardBody(FactoryHubLayout.calculate(bounds).body)
            val grid = UiRect(body.x, body.y + 13, body.width, body.height - 13)
            assertEquals(6, MccHubPortraitCards.grid(grid, 6).size, "$bounds draft")
            assertEquals(4, MccHubPortraitCards.grid(grid, 4).size, "$bounds team")
        }
    }

    @Test
    fun `the swap decision fits the team beside three offers`() {
        sizes.forEach { bounds ->
            val (team, offers) = MccHubKit.columns(FactoryHubLayout.calculate(bounds).body, 1, 1)
            val teamBody = MccHubKit.cardBody(team)
            val teamGrid = UiRect(teamBody.x, teamBody.y + 13, teamBody.width, teamBody.height - 13)
            assertEquals(4, MccHubPortraitCards.grid(teamGrid, 4).size, "$bounds team")
            assertEquals(3, MccHubPortraitCards.grid(MccHubKit.cardBody(offers), 3).size, "$bounds offers")
        }
    }
}
