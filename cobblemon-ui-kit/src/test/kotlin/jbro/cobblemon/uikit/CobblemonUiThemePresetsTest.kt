package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
                "pixel_league",
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

    @Test
    fun `pixel league is a framed sprite backed theme rather than a palette swap`() {
        val snapshot = CobblemonUiThemePresets.snapshot(UiThemePreset.PIXEL_LEAGUE)

        assertTrue(snapshot.surfaces.shell.border is UiBorder.PixelFrame)
        assertTrue(snapshot.surfaces.panel.border is UiBorder.PixelFrame)
        assertEquals(2, (snapshot.surfaces.shell.border as UiBorder.PixelFrame).shadowOffset)
        assertEquals(1, (snapshot.surfaces.panel.border as UiBorder.PixelFrame).shadowOffset)
        assertTrue(snapshot.surfaces.shell.fill is UiFill.Solid)
        assertNotNull(snapshot.surfaces.panelText)
        assertNotNull(snapshot.surfaces.panelAltText)
        assertNotEquals(snapshot.colors.textPrimary, snapshot.surfaces.panelText)
        assertNotEquals(CobblemonUiDefaultTheme.metrics, UiControlSize.entries.associateWith(snapshot::metrics))

        listOf(
            UiButtonVariant.PRIMARY,
            UiButtonVariant.SECONDARY,
            UiButtonVariant.DANGER,
            UiButtonVariant.ICON
        ).forEach { variant ->
            val normal = snapshot.style(variant, UiWidgetState.NORMAL)
            val focused = snapshot.style(variant, UiWidgetState.FOCUS)
            val selected = snapshot.style(variant, UiWidgetState.SELECTED)

            assertTrue(normal.surface.border is UiBorder.PixelFrame, variant.name)
            assertTrue(normal.surface.fill is UiFill.Solid, variant.name)
            assertNotEquals(UiSelectionIndicator.None, focused.selectionIndicator, variant.name)
            assertNotEquals(UiSelectionIndicator.None, selected.selectionIndicator, variant.name)
        }

        assertNotNull(snapshot.pixelDecorations)
    }
}
