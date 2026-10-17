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
    // English at about 6px a letter, with the long titles that once pushed the labels above the chips.
    private val english = listOf(
        PvpRoomSettingsFit.Row(60, listOf(36, 42)),
        PvpRoomSettingsFit.Row(78, listOf(36, 36)),
        PvpRoomSettingsFit.Row(102, listOf(24, 30, 24, 36)),
        PvpRoomSettingsFit.Row(72, listOf(42, 30)),
    )
    private val englishShort = listOf(36, 36, 42, 30).zip(english) { title, row -> PvpRoomSettingsFit.Row(title, row.chipWidths) }
    private val koreanShort = rows.map { PvpRoomSettingsFit.Row(18, it.chipWidths) }
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test
    fun `every hub size fits all four settings rows in Korean and English`() {
        listOf(rows to koreanShort, english to englishShort).forEach { (full, short) ->
            sizes.forEach { bounds ->
                val fit = PvpRoomSettingsFit.fit(PvpHubLayout.calculate(bounds).body, full, short)
                val settings = MccHubKit.cardBody(fit.columns[2])
                val shown = if (fit.shortTitles) short else full
                assertTrue(PvpRoomSettingsFit.height(settings.width, shown) <= settings.height, "$bounds $settings short=${fit.shortTitles}")
            }
        }
    }

    @Test
    fun `a roomy hub keeps the usual split and the full titles`() {
        val body = PvpHubLayout.calculate(UiRect(40, 20, 590, 340)).body
        val fit = PvpRoomSettingsFit.fit(body, english, englishShort)
        assertEquals(MccHubKit.columns(body, 3, 3, 4), fit.columns)
        assertEquals(false, fit.shortTitles)
    }

    @Test
    fun `labels beside the chips take one line a row, labels above take two`() {
        assertEquals(4 * 14 + 3 * 4, PvpRoomSettingsFit.height(400, rows))
        assertTrue(PvpRoomSettingsFit.height(90, rows) > PvpRoomSettingsFit.height(400, rows))
    }
}
