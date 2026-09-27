package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.CobblemonUiThemePresets
import jbro.cobblemon.uikit.UiThemePreset
import jbro.cobblemon.uikit.UiThemeSnapshot

/**
 * The UI kit theme the hub installs while it is open, and its dialogs use. Every hub piece takes its colours
 * and control sizes from the installed theme, so switching [preset] restyles the hub and all content tabs.
 */
object MccHubTheme {
    var preset: UiThemePreset = UiThemePreset.PIXEL_LEAGUE

    fun snapshot(): UiThemeSnapshot = CobblemonUiThemePresets.snapshot(preset)
}
