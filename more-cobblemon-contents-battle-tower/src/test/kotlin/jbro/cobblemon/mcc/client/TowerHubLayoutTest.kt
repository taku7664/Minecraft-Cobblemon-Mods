package jbro.cobblemon.mcc.client

import com.google.gson.JsonParser
import java.io.InputStreamReader
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerHubLayoutTest {
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `every hub size keeps strip cards and footer inside the bounds without overlap`() {
        sizes.forEach { bounds ->
            val layout = TowerHubLayout.calculate(bounds)
            assertTrue(layout.strip.bottom <= layout.party.y, "$bounds")
            assertTrue(layout.party.right < layout.setup.x, "$bounds")
            assertTrue(layout.party.bottom <= layout.footer.y, "$bounds")
            assertEquals(bounds.right, layout.setup.right, "$bounds")
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
        }
    }

    @Test
    fun `setup card always fits its three settings`() {
        sizes.forEach { bounds ->
            val body = MccHubKit.cardBody(TowerHubLayout.calculate(bounds).setup)
            assertTrue(body.height >= MccHubKit.CONTROL_HEIGHT * 3 + MccHubKit.GAP * 2, "$bounds body ${body.height}")
        }
    }

    @Test
    fun `party grid fits six cards in the party card at every size`() {
        sizes.forEach { bounds ->
            val body = MccHubKit.cardBody(TowerHubLayout.calculate(bounds).party)
            assertEquals(TowerHubLayout.PARTY_SIZE, MccHubPortraitCards.grid(body, TowerHubLayout.PARTY_SIZE).size, "$bounds")
        }
    }

    @Test
    fun `both languages define every guide section`() {
        listOf("en_us", "ko_kr").forEach { language ->
            val stream = requireNotNull(javaClass.getResourceAsStream(
                "/assets/more_cobblemon_contents_battle_tower/lang/$language.json",
            ))
            val entries = InputStreamReader(stream).use(JsonParser::parseReader).asJsonObject
            assertTrue(entries.has(TowerGuideContent.TITLE_KEY))
            TowerGuideContent.sections.forEach { section ->
                assertTrue(entries.has(section.titleKey), "$language is missing ${section.titleKey}")
                assertTrue(entries.has(section.bodyKey), "$language is missing ${section.bodyKey}")
            }
        }
    }
}
