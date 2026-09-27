package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.CobblemonUiThemeComposer
import jbro.cobblemon.uikit.UiPalettePreset
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiThemeStyle

/**
 * The UI kit theme the hub installs while it is open, and its dialogs use: a UI kit [style] drawn in a [palette].
 * Every hub piece takes its shapes, colours and control sizes from the installed theme, so changing either one
 * restyles the hub and every content tab.
 */
object MccHubTheme {
    var style: UiThemeStyle = UiThemeStyle.DS_WINDOW
    var palette: UiPalettePreset = UiPalettePreset.TOWER_LOBBY

    /** The theme id, as the UI kit registry reports it while the hub is open. */
    val id: String get() = CobblemonUiThemeComposer.id(style, palette)

    fun snapshot(): UiThemeSnapshot = CobblemonUiThemeComposer.compose(style, palette)

    /** Selects a theme by its id, `style.palette` (for example `ds_window.tower_lobby`); false if either is unknown. */
    fun select(id: String): Boolean {
        val style = UiThemeStyle.entries.firstOrNull { id.startsWith(it.id + ".") } ?: return false
        val palette = UiPalettePreset.fromId(id.removePrefix(style.id + ".")) ?: return false
        this.style = style
        this.palette = palette
        return true
    }
}
