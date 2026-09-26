package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonStyle
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiSelectionIndicator
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
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
        UiSurfaceRenderer.draw(
            graphics,
            x,
            y,
            width,
            height,
            style.surface.resolve(spec.surfaceOverrides)
        )
        drawContent(graphics, theme, style)
    }

    private fun displayedState(): UiWidgetState = forcedState ?: when {
        !active -> UiWidgetState.DISABLED
        Util.getMillis() < pressedUntil -> UiWidgetState.PRESSED
        isFocused -> UiWidgetState.FOCUS
        isHovered -> UiWidgetState.HOVER
        spec.selected -> UiWidgetState.SELECTED
        else -> UiWidgetState.NORMAL
    }

    private fun drawContent(graphics: GuiGraphics, theme: UiThemeSnapshot, style: UiButtonStyle) {
        val font = Minecraft.getInstance().font
        val metrics = theme.metrics(spec.size)
        val iconSize = minOf(metrics.iconSize, PIXEL_SPRITE_SIZE)
        val indicator = style.selectionIndicator as? UiSelectionIndicator.Sprite
        val indicatorAndGap = if (indicator == null) 0 else iconSize + metrics.iconGap
        val iconAndGap = if (spec.icon == null) 0 else iconSize + metrics.iconGap
        val titleWidth = (font.width(spec.title) * metrics.titleScale).toInt()
        val supportingWidth = spec.supportingText?.let {
            (font.width(it) * metrics.supportingScale).toInt()
        } ?: 0
        val textWidth = max(titleWidth, supportingWidth)
        val groupWidth = indicatorAndGap + iconAndGap + textWidth
        val groupLeft = x + (width - groupWidth) / 2
        val textCenter = groupLeft + indicatorAndGap + iconAndGap + textWidth / 2
        val contentOffsetY = style.pressedOffsetY
        val textShadow = spec.resolveTextShadow(style)

        if (indicator != null) {
            val indicatorTop = y + (height - iconSize) / 2 + contentOffsetY
            drawSprite(graphics, indicator.icon, groupLeft, indicatorTop, iconSize)
        }

        if (spec.icon != null) {
            val iconLeft = groupLeft + indicatorAndGap
            val iconTop = y + (height - iconSize) / 2 + contentOffsetY
            drawSprite(graphics, spec.icon, iconLeft, iconTop, iconSize)
        }

        if (spec.supportingText == null) {
            drawScaledCentered(
                graphics,
                spec.title,
                textCenter,
                y + (height - (font.lineHeight * metrics.titleScale).toInt()) / 2 + contentOffsetY,
                metrics.titleScale,
                style.text,
                textShadow
            )
        } else {
            drawScaledCentered(
                graphics,
                spec.title,
                textCenter,
                y + 5 + contentOffsetY,
                metrics.titleScale,
                style.text,
                textShadow
            )
            drawScaledCentered(
                graphics,
                spec.supportingText,
                textCenter,
                y + height - (font.lineHeight * metrics.supportingScale).toInt() - 5 + contentOffsetY,
                metrics.supportingScale,
                style.supportingText,
                textShadow
            )
        }
    }

    private fun drawSprite(graphics: GuiGraphics, icon: UiIcon, left: Int, top: Int, size: Int) {
        graphics.blit(
            ResourceLocation.fromNamespaceAndPath(icon.namespace, icon.path),
            left,
            top,
            0f,
            0f,
            size,
            size,
            PIXEL_SPRITE_SIZE,
            PIXEL_SPRITE_SIZE
        )
    }

    companion object {
        private const val PRESSED_MILLIS = 120L
        private const val PIXEL_SPRITE_SIZE = 8

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
            color: Int,
            shadow: Boolean
        ) {
            val font = Minecraft.getInstance().font
            graphics.pose().pushPose()
            try {
                graphics.pose().scale(scale, scale, 1f)
                val scaledCenterX = (centerX / scale).toInt()
                graphics.drawString(
                    font,
                    text,
                    scaledCenterX - font.width(text) / 2,
                    (top / scale).toInt(),
                    color,
                    shadow
                )
            } finally {
                graphics.pose().popPose()
            }
        }
    }
}
