package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import kotlin.math.max

class CobblemonUiButton private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiButtonSpec,
    private val forcedState: UiWidgetState?,
    private val press: () -> Unit
) : AbstractButton(x, y, width, height, spec.title) {
    private var pressedUntil = 0L

    init {
        active = forcedState != UiWidgetState.DISABLED
    }

    override fun onPress() {
        if (!active) return
        pressedUntil = Util.getMillis() + PRESSED_MILLIS
        press()
    }

    override fun createNarrationMessage(): MutableComponent {
        val message = Component.empty().append(spec.title)
        spec.supportingText?.let { message.append(Component.literal(". ")).append(it) }
        return wrapDefaultNarrationMessage(message)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val state = displayedState()
        val style = theme.style(spec.variant, state)
        drawSurface(graphics, style.background, style.border)
        drawContent(graphics, theme, style.text, style.supportingText)
    }

    private fun displayedState(): UiWidgetState = forcedState ?: when {
        !active -> UiWidgetState.DISABLED
        Util.getMillis() < pressedUntil -> UiWidgetState.PRESSED
        isFocused -> UiWidgetState.FOCUS
        isHovered -> UiWidgetState.HOVER
        spec.selected -> UiWidgetState.SELECTED
        else -> UiWidgetState.NORMAL
    }

    private fun drawSurface(graphics: GuiGraphics, background: Int, border: Int) {
        graphics.fill(x, y, x + width, y + height, background)
        graphics.fill(x, y, x + width, y + 1, border)
        graphics.fill(x, y + height - 1, x + width, y + height, border)
        graphics.fill(x, y, x + 1, y + height, border)
        graphics.fill(x + width - 1, y, x + width, y + height, border)
        if (spec.variant != UiButtonVariant.GHOST) {
            graphics.fill(x + 1, y + 1, x + 3, y + height - 1, border)
        }
    }

    private fun drawContent(graphics: GuiGraphics, theme: UiThemeSnapshot, textColor: Int, supportingColor: Int) {
        val font = Minecraft.getInstance().font
        val metrics = theme.metrics(spec.size)
        val iconAndGap = if (spec.icon == null) 0 else metrics.iconSize + metrics.iconGap
        val titleWidth = (font.width(spec.title) * metrics.titleScale).toInt()
        val supportingWidth = spec.supportingText?.let {
            (font.width(it) * metrics.supportingScale).toInt()
        } ?: 0
        val textWidth = max(titleWidth, supportingWidth)
        val groupWidth = iconAndGap + textWidth
        val groupLeft = x + (width - groupWidth) / 2
        val textCenter = groupLeft + iconAndGap + textWidth / 2

        if (spec.icon != null) {
            val iconTop = y + (height - metrics.iconSize) / 2
            graphics.fill(
                groupLeft,
                iconTop,
                groupLeft + metrics.iconSize,
                iconTop + metrics.iconSize,
                theme.colors.accentSecondary
            )
            graphics.fill(
                groupLeft + 2,
                iconTop + 2,
                groupLeft + metrics.iconSize - 2,
                iconTop + metrics.iconSize - 2,
                theme.colors.textPrimary
            )
        }

        if (spec.supportingText == null) {
            drawScaledCentered(
                graphics,
                spec.title,
                textCenter,
                y + (height - (font.lineHeight * metrics.titleScale).toInt()) / 2,
                metrics.titleScale,
                textColor
            )
        } else {
            drawScaledCentered(graphics, spec.title, textCenter, y + 5, metrics.titleScale, textColor)
            drawScaledCentered(
                graphics,
                spec.supportingText,
                textCenter,
                y + height - (font.lineHeight * metrics.supportingScale).toInt() - 5,
                metrics.supportingScale,
                supportingColor
            )
        }
    }

    companion object {
        private const val PRESSED_MILLIS = 120L

        fun create(
            x: Int,
            y: Int,
            availableWidth: Int,
            spec: UiButtonSpec,
            forcedState: UiWidgetState? = null,
            press: () -> Unit = {}
        ): CobblemonUiButton {
            val theme = CobblemonUiThemes.registry.snapshot()
            val metrics = theme.metrics(spec.size)
            val font = Minecraft.getInstance().font
            val titleWidth = (font.width(spec.title) * metrics.titleScale).toInt()
            val supportingWidth = spec.supportingText?.let {
                (font.width(it) * metrics.supportingScale).toInt()
            } ?: 0
            val width = spec.resolveWidth(max(titleWidth, supportingWidth), availableWidth, theme)
            return CobblemonUiButton(x, y, width, spec.resolveHeight(theme), spec, forcedState, press)
        }

        private fun drawScaledCentered(
            graphics: GuiGraphics,
            text: Component,
            centerX: Int,
            top: Int,
            scale: Float,
            color: Int
        ) {
            val font = Minecraft.getInstance().font
            graphics.pose().pushPose()
            try {
                graphics.pose().scale(scale, scale, 1f)
                graphics.drawCenteredString(
                    font,
                    text,
                    (centerX / scale).toInt(),
                    (top / scale).toInt(),
                    color
                )
            } finally {
                graphics.pose().popPose()
            }
        }
    }
}
