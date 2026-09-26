package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CobblemonUiThemePresetsTest {
    @Test
    fun `built in presets expose stable unique ids`() {
        assertEquals(
            listOf(
                "league_neon",
                "galar_stadium",
                "paldea_portal",
                "hoenn_pixel",
                "johto_touch",
                "unova_pixel"
            ),
            UiThemePreset.entries.map(UiThemePreset::id)
        )
        assertEquals(UiThemePreset.entries.size, UiThemePreset.entries.map(UiThemePreset::id).toSet().size)
    }

    @Test
    fun `preset lookup is explicit and unknown ids do not silently match`() {
        assertEquals(UiThemePreset.GALAR_STADIUM, UiThemePreset.fromId("galar_stadium"))
        assertNull(UiThemePreset.fromId("GALAR_STADIUM"))
        assertNull(UiThemePreset.fromId("hisui"))
    }

    @Test
    fun `every preset supplies every metric and button state`() {
        UiThemePreset.entries.forEach { preset ->
            val snapshot = CobblemonUiThemePresets.snapshot(preset)
            assertEquals(preset.id, snapshot.id)
            UiControlSize.entries.forEach { size ->
                assertTrue(snapshot.metrics(size).height >= 18)
            }
            UiButtonVariant.entries.forEach { variant ->
                UiWidgetState.entries.forEach { state ->
                    snapshot.style(variant, state)
                }
            }
        }
    }

    @Test
    fun `preset install swaps the shared registry snapshot`() {
        val before = CobblemonUiThemes.registry.snapshot()

        CobblemonUiThemePresets.install(UiThemePreset.UNOVA_PIXEL)

        assertSame(
            CobblemonUiThemePresets.snapshot(UiThemePreset.UNOVA_PIXEL),
            CobblemonUiThemes.registry.snapshot()
        )
        assertNotEquals(before.id, CobblemonUiThemes.registry.snapshot().id)

        CobblemonUiThemePresets.install(UiThemePreset.LEAGUE_NEON)
    }
}
