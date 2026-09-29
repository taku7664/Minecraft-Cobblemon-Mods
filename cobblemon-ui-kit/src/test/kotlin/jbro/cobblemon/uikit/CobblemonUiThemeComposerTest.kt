package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CobblemonUiThemeComposerTest {
    @Test
    fun `every style and palette pairing builds a complete theme under a stable id`() {
        UiThemeStyle.entries.forEach { style ->
            UiPalettePreset.entries.forEach { palette ->
                val snapshot = CobblemonUiThemeComposer.compose(style, palette)
                assertEquals("${style.id}.${palette.id}", snapshot.id)
                UiButtonVariant.entries.forEach { variant ->
                    UiWidgetState.entries.forEach { state -> snapshot.style(variant, state) }
                }
                UiWidgetState.entries.forEach { state -> snapshot.listRowStyle(state) }
            }
        }
    }

    @Test
    fun `a DS window theme can be the first theme built`() {
        // In the game the hub composes its DS window theme before anything touches the presets; a fresh class
        // loader repeats that order, which once recursed through the composer's cache.
        val classes = CobblemonUiThemeComposer::class.java.protectionDomain.codeSource.location
        val loader = object : java.net.URLClassLoader(arrayOf(classes), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (!name.startsWith("jbro.cobblemon.uikit.")) return super.loadClass(name, resolve)
                findLoadedClass(name) ?: findClass(name)
            }
        }
        loader.use {
            val composer = it.loadClass(CobblemonUiThemeComposer::class.java.name)
            val style = it.loadClass(UiThemeStyle::class.java.name)
            val palette = it.loadClass(UiPalettePreset::class.java.name)
            val instance = composer.getField("INSTANCE").get(null)
            val compose = composer.getMethod("compose", style, palette, String::class.java)
            val ds = style.getField("DS_WINDOW").get(null)
            val lobby = palette.getField("TOWER_LOBBY").get(null)
            val snapshot = compose.invoke(instance, ds, lobby, "ds_window.tower_lobby")
            assertEquals("ds_window.tower_lobby", snapshot.javaClass.getMethod("getId").invoke(snapshot))
            // The presets object starts afterwards and composes its own pixel frame themes without trouble.
            val presets = it.loadClass(CobblemonUiThemePresets::class.java.name)
            val preset = it.loadClass(UiThemePreset::class.java.name)
            val tower = presets.getMethod("snapshot", preset).invoke(presets.getField("INSTANCE").get(null), preset.getField("TOWER_LOBBY").get(null))
            assertEquals("tower_lobby", tower.javaClass.getMethod("getId").invoke(tower))
        }
    }

    @Test
    fun `a pairing is built once`() {
        assertSame(
            CobblemonUiThemeComposer.compose(UiThemeStyle.DS_WINDOW, UiPalettePreset.TOWER_LOBBY),
            CobblemonUiThemeComposer.compose(UiThemeStyle.DS_WINDOW, UiPalettePreset.TOWER_LOBBY)
        )
    }

    @Test
    fun `the tower and factory presets are the pixel frame style of their palettes`() {
        val preset = CobblemonUiThemePresets.snapshot(UiThemePreset.TOWER_LOBBY)
        assertEquals("tower_lobby", preset.id)
        assertInstanceOf(UiBorder.PixelFrame::class.java, preset.surfaces.panel.border)
        assertEquals(UiPanelTitleStyle.BAND, preset.surfaces.panelTitle)
    }

    @Test
    fun `the DS window style frames windows, heads them with a rule and marks choices with the cursor`() {
        val palette = UiPalettePreset.TOWER_LOBBY
        val snapshot = CobblemonUiThemeComposer.compose(UiThemeStyle.DS_WINDOW, palette)

        val frame = assertInstanceOf(UiBorder.WindowFrame::class.java, snapshot.surfaces.panel.border)
        assertEquals(palette.palette.infoBand, frame.bandColor)
        assertInstanceOf(UiShape.RoundedRectangle::class.java, snapshot.surfaces.panel.shape)
        assertEquals(UiPanelTitleStyle.RULE, snapshot.surfaces.panelTitle)

        val normal = snapshot.style(UiButtonVariant.SECONDARY, UiWidgetState.NORMAL)
        val selected = snapshot.style(UiButtonVariant.SECONDARY, UiWidgetState.SELECTED)
        // The cursor marks the choice; the fill stays as it was.
        assertEquals(normal.surface.fill, selected.surface.fill)
        assertEquals(UiSelectionIndicator.Outline(palette.palette.cursor, 2), selected.selectionIndicator)
        assertEquals(palette.palette.textShadow, normal.textShadowColor)

        val row = snapshot.listRowStyle(UiWidgetState.NORMAL)
        assertEquals(UiFill.None, row.surface.fill)
        assertEquals(UiBorder.None, row.surface.border)
        assertEquals(UiSelectionIndicator.Outline(palette.palette.cursor, 2), snapshot.listRowStyle(UiWidgetState.SELECTED).selectionIndicator)
    }

    @Test
    fun `themes without row styles draw rows as secondary buttons`() {
        val pixel = CobblemonUiThemePresets.snapshot(UiThemePreset.PIXEL_LEAGUE)
        assertSame(pixel.style(UiButtonVariant.SECONDARY, UiWidgetState.HOVER), pixel.listRowStyle(UiWidgetState.HOVER))
    }

    @Test
    fun `palette lookup is explicit`() {
        assertEquals(UiPalettePreset.FACTORY_NIGHT, UiPalettePreset.fromId("factory_night"))
        assertNull(UiPalettePreset.fromId("FACTORY_NIGHT"))
    }

    @Test
    fun `window frame thickness counts its outline, band and inner line`() {
        assertEquals(3, UiBorder.WindowFrame(0, 0, 2).thickness)
        assertEquals(4, UiBorder.WindowFrame(0, 0, 2, innerColor = 0).thickness)
        assertThrows(IllegalArgumentException::class.java) { UiBorder.WindowFrame(0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { UiSelectionIndicator.Outline(0, 0) }
    }
}
