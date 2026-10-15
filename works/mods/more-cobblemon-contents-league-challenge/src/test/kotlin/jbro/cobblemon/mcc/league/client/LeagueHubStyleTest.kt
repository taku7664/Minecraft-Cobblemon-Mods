package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.uikit.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueHubStyleTest {
    @Test fun `reference card styling never replaces the shared tab theme`() {
        val installed = CobblemonUiThemes.registry.snapshot()
        val selected = CobblemonUiSharedTheme.id
        val local = LeagueHubStyle.theme
        assertEquals(UiShape.Rectangle, local.surfaces.panelAlt.shape)
        assertInstanceOf(UiBorder.PixelFrame::class.java, local.surfaces.panelAlt.border)
        assertSame(installed, CobblemonUiThemes.registry.snapshot())
        assertEquals(selected, CobblemonUiSharedTheme.id)
    }

    @Test fun `card and action colours match the supplied reference`() {
        val theme = LeagueHubStyle.theme
        assertEquals(UiFill.Solid(0xFFD6DADC.toInt()), theme.surfaces.panelAlt.fill)
        assertEquals(0xFFC04038.toInt(), theme.colors.accentCaution)
        assertEquals(0xFF8098B0.toInt(), theme.colors.borderBright)
        assertEquals(UiFill.Solid(0xFF2C9CCC.toInt()), theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL).surface.fill)
    }
}
