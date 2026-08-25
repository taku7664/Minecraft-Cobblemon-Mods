package jbro.cobblemon.morebattlecontent.client

import com.google.gson.JsonParser
import java.io.InputStreamReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerGuideLayoutTest {
    @Test
    fun `guide keeps header viewport and scrollbar inside its shell on compact screens`() {
        val layout = TowerGuideLayout.calculate(320, 240)

        assertTrue(layout.shell.contains(layout.header))
        assertTrue(layout.header.contains(layout.closeButton))
        assertTrue(layout.shell.contains(layout.viewport))
        assertTrue(layout.shell.contains(layout.scrollTrack))
        assertTrue(layout.viewport.right < layout.scrollTrack.left)
        assertEquals(layout.viewport.top, layout.scrollTrack.top)
        assertEquals(layout.viewport.bottom, layout.scrollTrack.bottom)
    }

    @Test
    fun `guide scrollbar reaches the full wrapped content range`() {
        val layout = TowerGuideLayout.calculate(854, 480)
        val metrics = MbcVerticalScrollMetrics.calculate(
            viewportHeight = layout.viewport.height,
            contentHeight = layout.viewport.height * 3,
            trackHeight = layout.scrollTrack.height,
        )

        assertTrue(metrics.maxOffset > 0)
        assertEquals(0, metrics.offsetForThumbTop(layout.scrollTrack.top, layout.scrollTrack.top))
        assertEquals(
            metrics.maxOffset,
            metrics.offsetForThumbTop(layout.scrollTrack.top, layout.scrollTrack.bottom - metrics.thumbHeight),
        )
    }

    @Test
    fun `both languages contain every battle tower guide section`() {
        listOf("en_us", "ko_kr").forEach { language ->
            val stream = requireNotNull(javaClass.getResourceAsStream(
                "/assets/cobblemon_more_battle_content/lang/$language.json",
            ))
            val entries = InputStreamReader(stream).use(JsonParser::parseReader).asJsonObject

            assertTrue(entries.has(TowerGuideContent.TITLE_KEY))
            assertTrue(entries.has(TowerGuideContent.CLOSE_KEY))
            assertTrue(entries.has(TowerGuideContent.BUTTON_TOOLTIP_KEY))
            TowerGuideContent.sections.forEach { section ->
                assertTrue(entries.has(section.titleKey), "$language is missing ${section.titleKey}")
                assertTrue(entries.has(section.bodyKey), "$language is missing ${section.bodyKey}")
            }
        }
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
