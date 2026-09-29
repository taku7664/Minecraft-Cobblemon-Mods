package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiCardSpec
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiStatRowSpec
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

class CobblemonUiCard private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiCardSpec,
    private val press: () -> Unit
) : AbstractButton(x, y, width, height, spec.title) {
    override fun onPress() = press()
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val state = when {
            !active -> UiWidgetState.DISABLED
            isFocused -> UiWidgetState.FOCUS
            isHovered -> UiWidgetState.HOVER
            spec.selected -> UiWidgetState.SELECTED
            else -> UiWidgetState.NORMAL
        }
        val style = theme.style(UiButtonVariant.SECONDARY, state)
        UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface.copy(shape = UiShape.RoundedRectangle(4)))
        val font = Minecraft.getInstance().font
        val accent = when (spec.tone) {
            UiOverlayTone.NEUTRAL -> theme.colors.borderBright
            UiOverlayTone.INFO -> theme.colors.accentPrimary
            UiOverlayTone.SUCCESS -> theme.colors.accentGood
            UiOverlayTone.WARNING -> theme.colors.accentCaution
            UiOverlayTone.DANGER -> theme.colors.accentDanger
        }
        graphics.fill(x + 2, y + 3, x + 4, y + height - 3, accent)
        var textLeft = x + 9
        spec.icon?.let { icon ->
            graphics.blit(
                ResourceLocation.fromNamespaceAndPath(icon.namespace, icon.path),
                textLeft,
                y + (height - 8) / 2,
                0f,
                0f,
                8,
                8,
                8,
                8
            )
            textLeft += 12
        }
        graphics.drawString(font, spec.title, textLeft, y + 7, style.text, false)
        spec.body?.let { graphics.drawString(font, it, textLeft, y + 8 + font.lineHeight, style.supportingText, false) }
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiCardSpec, press: () -> Unit = {}): CobblemonUiCard {
            require(width > 0) { "Card width must be positive" }
            return CobblemonUiCard(x, y, width, if (spec.body == null) 28 else 42, spec, press)
        }
    }
}

class CobblemonUiStatRow private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiStatRowSpec
) : AbstractWidget(x, y, width, height, spec.label) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        graphics.drawString(font, spec.label, x, y, theme.colors.textSecondary, false)
        graphics.drawString(font, spec.value, x + width - font.width(spec.value), y, theme.colors.textPrimary, false)
        spec.displayProgress?.let { progress ->
            val top = y + font.lineHeight + 2
            UiSurfaceRenderer.draw(
                graphics,
                x,
                top,
                width,
                6,
                UiSurfaceStyle(UiShape.Capsule, UiFill.Solid(theme.colors.panelAlt), UiBorder.Solid(theme.colors.border))
            )
            val fillWidth = ((width - 2) * progress).toInt()
            if (fillWidth > 0) {
                UiSurfaceRenderer.draw(
                    graphics,
                    x + 1,
                    top + 1,
                    fillWidth,
                    4,
                    UiSurfaceStyle(UiShape.Capsule, UiFill.Solid(theme.colors.accentPrimary), UiBorder.None)
                )
            }
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, Component.empty().append(spec.label).append(Component.literal(": ")).append(spec.value))
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiStatRowSpec): CobblemonUiStatRow {
            require(width > 0) { "Stat row width must be positive" }
            return CobblemonUiStatRow(x, y, width, if (spec.progress == null) 10 else 18, spec)
        }
    }
}
