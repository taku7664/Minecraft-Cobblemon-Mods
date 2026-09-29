package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpHubLayoutTest {
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `every hub size keeps strip body and footer inside the bounds without overlap`() {
        sizes.forEach { bounds ->
            val layout = PvpHubLayout.calculate(bounds)
            assertTrue(layout.strip.bottom <= layout.body.y, "$bounds")
            assertTrue(layout.body.bottom <= layout.footer.y, "$bounds")
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
        }
    }

    @Test
    fun `a seat card keeps room for a model above its name and seat control`() {
        sizes.forEach { bounds ->
            val (left, _, settings) = MccHubKit.columns(PvpHubLayout.calculate(bounds).body, 3, 3, 4)
            val seat = MccHubKit.cardBody(left)
            assertTrue(seat.height - MccHubKit.CONTROL_HEIGHT - 14 >= 40, "$bounds seat ${seat.height}")
            assertTrue(MccHubKit.cardBody(settings).height >= MccHubKit.CONTROL_HEIGHT * 3 + MccHubKit.GAP * 2, "$bounds settings")
        }
    }

    @Test
    fun `both teams of six fit side by side during the team preview`() {
        sizes.forEach { bounds ->
            val (own, opponent) = MccHubKit.columns(PvpHubLayout.calculate(bounds).body, 1, 1)
            val ownBody = MccHubKit.cardBody(own)
            assertEquals(6, MccHubPortraitCards.grid(UiRect(ownBody.x, ownBody.y + 13, ownBody.width, ownBody.height - 13), 6).size, "$bounds own")
            assertEquals(6, MccHubPortraitCards.grid(MccHubKit.cardBody(opponent), 6).size, "$bounds opponent")
        }
    }
}
