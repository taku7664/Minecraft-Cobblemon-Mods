package jbro.cobblemon.mcc.client

import com.google.gson.JsonParser
import java.io.InputStreamReader
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `party cells stay inside the card and never overlap`() {
        sizes.forEach { bounds ->
            val body = MccHubKit.cardBody(TowerHubLayout.calculate(bounds).party)
            val cells = TowerHubLayout.partyCells(body)
            assertEquals(TowerHubLayout.PARTY_SIZE, cells.size)
            cells.forEach { cell ->
                assertTrue(body.contains(cell.bounds), "$bounds ${cell.bounds}")
                assertTrue(cell.bounds.contains(cell.portrait), "$bounds portrait")
                assertTrue(cell.bounds.contains(cell.text), "$bounds text")
                assertEquals(cell.portrait.width, cell.portrait.height)
            }
            cells.forEachIndexed { index, cell ->
                cells.drop(index + 1).forEach { other -> assertFalse(cell.bounds.overlaps(other.bounds), "$bounds") }
            }
        }
    }

    @Test
    fun `small hubs put the portrait beside the text and large ones above it`() {
        val small = TowerHubLayout.partyCells(MccHubKit.cardBody(TowerHubLayout.calculate(sizes.first()).party))
        val large = TowerHubLayout.partyCells(MccHubKit.cardBody(TowerHubLayout.calculate(sizes.last()).party))
        assertTrue(small.none { it.stacked })
        assertTrue(large.all { it.stacked })
        assertTrue(large.first().portrait.width > small.first().portrait.width)
    }

    @Test
    fun `both languages define the legendary choices and every guide section`() {
        listOf("en_us", "ko_kr").forEach { language ->
            val stream = requireNotNull(javaClass.getResourceAsStream(
                "/assets/more_cobblemon_contents_battle_tower/lang/$language.json",
            ))
            val entries = InputStreamReader(stream).use(JsonParser::parseReader).asJsonObject
            TowerLegendaryClassOption.entries.forEach { option ->
                assertTrue(entries.has(option.translationKey), "$language is missing ${option.translationKey}")
            }
            assertTrue(entries.has("screen.more_cobblemon_contents.tower.legendary_class.tooltip"))
            assertTrue(entries.has(TowerGuideContent.TITLE_KEY))
            TowerGuideContent.sections.forEach { section ->
                assertTrue(entries.has(section.titleKey), "$language is missing ${section.titleKey}")
                assertTrue(entries.has(section.bodyKey), "$language is missing ${section.bodyKey}")
            }
        }
    }
}

private fun UiRect.contains(other: UiRect): Boolean =
    other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

private fun UiRect.overlaps(other: UiRect): Boolean =
    x < other.right && other.x < right && y < other.bottom && other.y < bottom
