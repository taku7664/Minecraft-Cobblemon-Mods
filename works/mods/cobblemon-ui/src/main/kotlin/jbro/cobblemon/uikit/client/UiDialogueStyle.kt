package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * How message boxes look (battle narration, a scene's lines, an NPC's talk): in the content theme the hub's cards
 * use, so a box reads as one of those cards. The box is a card body, its name plate takes the cards' feature band
 * colour in a control's frame, and the bars at the box's ends take the cards' information band colour. Where each
 * box sits and how big it is stay the screen's own business; the drawing is [drawBox], [drawPlate] and [drawArrow],
 * so every message box changes with them.
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

    /** The box and the bars at its ends, which start 2px in from the frame's rounded corners. */
    fun drawBox(graphics: GuiGraphics, theme: UiThemeSnapshot, box: UiRect) {
        UiSurfaceRenderer.draw(graphics, box.x, box.y, box.width, box.height, box(theme))
        val inset = inset(theme)
        graphics.fill(box.x + inset, box.y + inset + 2, box.x + inset + END_BAR, box.bottom - inset - 2, bar(theme))
        graphics.fill(box.right - inset - END_BAR, box.y + inset + 2, box.right - inset, box.bottom - inset - 2, bar(theme))
    }

    /** The name plate at [plate], its [name] starting at [textX]; whatever goes before the name is the caller's. */
    fun drawPlate(graphics: GuiGraphics, theme: UiThemeSnapshot, font: Font, plate: UiRect, name: Component, textX: Int) {
        UiSurfaceRenderer.draw(graphics, plate.x, plate.y, plate.width, plate.height, plate(theme))
        UiTextRenderer.draw(graphics, font, name, textX, UiTextRenderer.centeredY(font, name, plate.y, plate.height - 2),
            plateText(theme), null)
    }

    /** The little arrow that says there is more to read, its top left at ([x], [y]). */
    fun drawArrow(graphics: GuiGraphics, theme: UiThemeSnapshot, x: Int, y: Int, color: Int = arrow(theme)) {
        for (row in 0 until 4) graphics.fill(x + row, y + row, x + 7 - row, y + row + 1, color)
    }

    private const val END_BAR = 4
}
