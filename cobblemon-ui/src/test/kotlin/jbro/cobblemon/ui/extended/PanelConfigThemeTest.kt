package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleUiThemes
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PanelConfigThemeTest {
    @AfterEach
    fun restore() {
        PanelConfig.setBattleTheme("champions")
        PanelConfig.setUiTheme("ds_window", "tower_lobby")
    }

    @Test
    fun `the battle theme setting selects the theme at once`() {
        PanelConfig.setBattleTheme("galar")
        assertSame(BattleUiThemes.GALAR, BattleUiThemes.current)
        assertEquals("galar", PanelConfig.battleTheme)
        PanelConfig.setBattleTheme("no-such-theme")
        assertSame(BattleUiThemes.CHAMPIONS, BattleUiThemes.current)
        assertEquals("champions", PanelConfig.battleTheme)
    }

    @Test
    fun `style and colors change the shared look one at a time`() {
        PanelConfig.setUiPalette("factory_night")
        assertEquals("ds_window.factory_night", CobblemonUiSharedTheme.id)
        PanelConfig.setUiStyle("pixel_frame")
        assertEquals("pixel_frame.factory_night", CobblemonUiSharedTheme.id)
        assertEquals("pixel_frame", PanelConfig.uiStyle)
        assertEquals("factory_night", PanelConfig.uiPalette)
    }

    @Test
    fun `an unknown shared look falls back to the default`() {
        PanelConfig.setUiTheme("crayon", "nowhere")
        assertEquals("ds_window.tower_lobby", CobblemonUiSharedTheme.id)
        assertEquals("ds_window", PanelConfig.uiStyle)
        assertEquals("tower_lobby", PanelConfig.uiPalette)
    }
}
