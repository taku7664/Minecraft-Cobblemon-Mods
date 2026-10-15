package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState

/**
 * How message boxes look (battle narration, a scene's lines, an NPC's talk): in the content theme the hub's cards
 * use, so a box reads as one of those cards. The box is a card body, its name plate takes the cards' feature band
 * colour in a control's frame, and the bars at the box's ends take the cards' information band colour. Where each
 * box sits and how big it is stay the screen's own business.
 */
object UiDialogueStyle {
    /** The theme message boxes draw in. */
    fun theme(): UiThemeSnapshot = CobblemonUiSharedTheme.contentSnapshot()

    fun box(theme: UiThemeSnapshot): UiSurfaceStyle = theme.surfaces.panelAlt

    /** The text on the box and the shadow under it; pixel themes draw text flat. */
    fun ink(theme: UiThemeSnapshot): Int = theme.surfaces.panelAltText ?: theme.colors.textPrimary

    fun inkShadow(theme: UiThemeSnapshot): Int? =
        if (theme.pixelDecorations != null) null else theme.listRowStyle(UiWidgetState.NORMAL).textShadowColor

    /** Where the bars at the box's ends start, inside its frame. */
    fun inset(theme: UiThemeSnapshot): Int = CobblemonUiPanel.frameInset(box(theme))

    fun bar(theme: UiThemeSnapshot): Int = theme.colors.borderBright

    /** The speaker's name plate: a control's frame filled with the feature band colour, and the text that reads on it. */
    fun plate(theme: UiThemeSnapshot): UiSurfaceStyle =
        theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL).surface.copy(fill = UiFill.Solid(theme.colors.accentCaution))

    fun plateText(theme: UiThemeSnapshot): Int = CobblemonUiPanel.onColor(theme, theme.colors.accentCaution)

    /** The arrow that says there is more to read. */
    fun arrow(theme: UiThemeSnapshot): Int = theme.colors.accentPrimary
}
