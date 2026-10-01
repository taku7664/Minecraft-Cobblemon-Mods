package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A button in the shared look ([CobblemonUiSharedTheme]) on a screen that does not install it: over the chat, or on
 * the battle screen. The UI kit's own widgets read the installed theme, so this one draws from the shared theme
 * itself. [opacity] thins the button's fill over the world; its frame stays.
 */
class CobblemonUiThemedButton(
    bounds: UiRect,
    label: Component,
    private val variant: UiButtonVariant,
    private val opacity: Float = 1f,
    private val press: () -> Unit,
) : AbstractButton(bounds.x, bounds.y, bounds.width, bounds.height, label) {
    override fun onPress() = press()

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val state = when {
            !active -> UiWidgetState.DISABLED
            isHoveredOrFocused -> UiWidgetState.HOVER
            else -> UiWidgetState.NORMAL
        }
        draw(graphics, CobblemonUiSharedTheme.snapshot(), UiRect(x, y, width, height), message, variant, state, opacity)
    }

    companion object {
        /** A button face in [theme]'s [variant], its label centred and cut to fit: for a HUD that has no widgets. */
        fun draw(
            graphics: GuiGraphics,
            theme: UiThemeSnapshot,
            bounds: UiRect,
            label: Component,
            variant: UiButtonVariant,
            state: UiWidgetState = UiWidgetState.NORMAL,
            opacity: Float = 1f,
        ) {
            val style = theme.style(variant, state)
            UiSurfaceRenderer.draw(graphics, bounds.x, bounds.y, bounds.width, bounds.height,
                style.surface.copy(backgroundOpacity = style.surface.backgroundOpacity * opacity))
            val font = Minecraft.getInstance().font
            val text = UiTextRenderer.fitted(font, label, (bounds.width - 6).coerceAtLeast(1))
            val offset = if (state == UiWidgetState.PRESSED) style.pressedOffsetY else 0
            UiTextRenderer.draw(graphics, font, text, bounds.x + (bounds.width - font.width(text)) / 2,
                bounds.y + (bounds.height - font.lineHeight) / 2 + 1 + offset, style.text, style.textShadowColor)
            UiSurfaceRenderer.drawSelection(graphics, bounds.x, bounds.y, bounds.width, bounds.height, style.surface.shape,
                style.selectionIndicator)
        }
    }
}
