package jbro.cobblemon.uikit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CobblemonUiSharedThemeTest {
    @AfterEach
    fun resetSelection() {
        CobblemonUiSharedTheme.select("ds_window.tower_lobby")
    }

    @Test
    fun `the shared look is DS windows in the tower lobby palette by default`() {
        assertEquals("ds_window.tower_lobby", CobblemonUiSharedTheme.id)
        assertEquals(CobblemonUiSharedTheme.id, CobblemonUiSharedTheme.snapshot().id)
    }

    @Test
    fun `installing lasts until restored, and restoring puts the previous theme back`() {
        val registry = UiThemeRegistry(CobblemonUiDefaultTheme.snapshot)
        val restore = CobblemonUiSharedTheme.install(registry)
        assertEquals(CobblemonUiSharedTheme.id, registry.snapshot().id)
        restore()
        assertEquals(CobblemonUiDefaultTheme.snapshot.id, registry.snapshot().id)
    }

    @Test
    fun `selecting restyles an installed theme at once and survives the restore`() {
        val registry = UiThemeRegistry(CobblemonUiDefaultTheme.snapshot)
        val restore = CobblemonUiSharedTheme.install(registry)
        assertTrue(CobblemonUiSharedTheme.select("ds_window.factory_night", registry))
        assertEquals("ds_window.factory_night", registry.snapshot().id)
        restore()
        assertEquals(CobblemonUiDefaultTheme.snapshot.id, registry.snapshot().id)
    }

    @Test
    fun `restoring leaves a theme someone else installed meanwhile`() {
        val registry = UiThemeRegistry(CobblemonUiDefaultTheme.snapshot)
        val restore = CobblemonUiSharedTheme.install(registry)
        val other = CobblemonUiThemeComposer.compose(UiThemeStyle.PIXEL_FRAME, UiPalettePreset.TOWER_LOBBY)
        registry.install(other)
        restore()
        assertEquals(other.id, registry.snapshot().id)
    }

    @Test
    fun `unknown ids select nothing`() {
        assertFalse(CobblemonUiSharedTheme.select("ds_window.nowhere"))
        assertFalse(CobblemonUiSharedTheme.select("crayon.tower_lobby"))
        assertEquals("ds_window.tower_lobby", CobblemonUiSharedTheme.id)
    }
}
