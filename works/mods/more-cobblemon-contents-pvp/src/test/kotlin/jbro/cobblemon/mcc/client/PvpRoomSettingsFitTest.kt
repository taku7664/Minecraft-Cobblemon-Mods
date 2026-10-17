package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomSettingsFitTest {
    // The four Korean settings rows at the default font's width (a Hangul syllable about 9px): visibility, format,
    // the four gimmicks and the two duplicate rules.
    private val rows = listOf(
        PvpRoomSettingsFit.Row(40, listOf(18, 27)),
        PvpRoomSettingsFit.Row(40, listOf(18, 18)),
        PvpRoomSettingsFit.Row(40, listOf(18, 36, 18, 24)),
        PvpRoomSettingsFit.Row(40, listOf(27, 18)),
    )
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `every hub size fits all four settings rows`() {
        sizes.forEach { bounds ->
            val settings = MccHubKit.cardBody(PvpRoomSettingsFit.columns(PvpHubLayout.calculate(bounds).body, rows)[2])
            assertTrue(PvpRoomSettingsFit.height(settings.width, rows) <= settings.height, "$bounds $settings")
        }
    }

    @Test
    fun `a roomy hub keeps the usual split`() {
        val body = PvpHubLayout.calculate(UiRect(40, 20, 590, 340)).body
        assertEquals(MccHubKit.columns(body, 3, 3, 4), PvpRoomSettingsFit.columns(body, rows))
    }

    @Test
    fun `labels beside the chips take one line a row, labels above take two`() {
        assertEquals(4 * 14 + 3 * 4, PvpRoomSettingsFit.height(400, rows))
        assertTrue(PvpRoomSettingsFit.height(90, rows) > PvpRoomSettingsFit.height(400, rows))
    }
}
