package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiAxis
import jbro.cobblemon.uikit.UiCalloutSpec
import jbro.cobblemon.uikit.UiOrderedSelectionState
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.UiPanelSpec
import jbro.cobblemon.uikit.UiPanelTone
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiStepState
import jbro.cobblemon.uikit.UiStepTrackSpec
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiTextAlignment
import jbro.cobblemon.uikit.UiTextSpec
import jbro.cobblemon.uikit.UiTextTone
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiButtonVariant
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW

class CobblemonUiTextBlock private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiTextSpec,
    private val lines: List<FormattedCharSequence>,
    private val truncated: Boolean
) : AbstractWidget(x, y, width, height, spec.text) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val color = textColor(spec.tone)
        lines.forEachIndexed { index, line ->
            val lineWidth = font.width(line)
            val left = when (spec.alignment) {
                UiTextAlignment.START -> x
                UiTextAlignment.CENTER -> x + (width - lineWidth) / 2
                UiTextAlignment.END -> x + width - lineWidth
            }
            graphics.drawString(font, line, left, y + index * (font.lineHeight + spec.lineSpacing), color, spec.shadow)
        }
        if (truncated && spec.ellipsis && lines.isNotEmpty()) {
            val ellipsis = Component.literal("…")
            graphics.drawString(
                font,
                ellipsis,
                x + width - font.width(ellipsis),
                y + (lines.size - 1) * (font.lineHeight + spec.lineSpacing),
                color,
                spec.shadow
            )
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, spec.text)
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiTextSpec): CobblemonUiTextBlock {
            require(width > 0) { "Text block width must be positive" }
            val font = Minecraft.getInstance().font
            val allLines = font.split(spec.text, width)
            val visibleLines = allLines.take(spec.maxLines)
            val height = visibleLines.size * font.lineHeight + (visibleLines.size - 1).coerceAtLeast(0) * spec.lineSpacing
            return CobblemonUiTextBlock(x, y, width, height, spec, visibleLines, allLines.size > visibleLines.size)
        }
    }
}

class CobblemonUiPanel private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiPanelSpec
) : AbstractWidget(x, y, width, height, spec.title ?: Component.empty()) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val (surface, textColor) = when (spec.tone) {
            UiPanelTone.SHELL -> theme.surfaces.shell to (theme.surfaces.shellText ?: theme.colors.textPrimary)
            UiPanelTone.PANEL -> theme.surfaces.panel to (theme.surfaces.panelText ?: theme.colors.textPrimary)
            UiPanelTone.RAISED -> theme.surfaces.panelAlt to (theme.surfaces.panelAltText ?: theme.colors.textPrimary)
        }
        UiSurfaceRenderer.draw(graphics, x, y, width, height, surface)
        spec.title?.let {
            graphics.drawString(
                Minecraft.getInstance().font,
                it,
                x + spec.padding.left,
                y + spec.padding.top,
                textColor,
                false
            )
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        spec.title?.let { output.add(NarratedElementType.TITLE, it) }
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, height: Int, spec: UiPanelSpec = UiPanelSpec()): CobblemonUiPanel {
            require(width > 0 && height > 0) { "Panel size must be positive" }
            return CobblemonUiPanel(x, y, width, height, spec)
        }
    }
}

class CobblemonUiCallout private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiCalloutSpec,
    private val bodyLines: List<FormattedCharSequence>
) : AbstractWidget(x, y, width, height, spec.title) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val accent = toneColor(spec.tone)
        UiSurfaceRenderer.draw(graphics, x, y, width, height, theme.surfaces.panelAlt)
        graphics.fill(x + 2, y + 2, x + 5, y + height - 2, accent)
        val font = Minecraft.getInstance().font
        val panelText = theme.surfaces.panelAltText ?: theme.colors.textPrimary
        graphics.drawString(font, spec.title, x + 9, y + 6, panelText, false)
        bodyLines.forEachIndexed { index, line ->
            graphics.drawString(font, line, x + 9, y + 8 + font.lineHeight + index * (font.lineHeight + 1), panelText, false)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        val narration = Component.empty().append(spec.title)
        spec.body?.let { narration.append(Component.literal(". ")).append(it) }
        output.add(NarratedElementType.TITLE, narration)
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiCalloutSpec): CobblemonUiCallout {
            require(width > 0) { "Callout width must be positive" }
            val font = Minecraft.getInstance().font
            val lines = spec.body?.let { font.split(it, (width - 18).coerceAtLeast(1)) } ?: emptyList()
            val height = if (lines.isEmpty()) 22 else 17 + lines.size * (font.lineHeight + 1)
            return CobblemonUiCallout(x, y, width, height, spec, lines)
        }
    }
}

class CobblemonUiStepTrack private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiStepTrackSpec
) : AbstractWidget(x, y, width, height, Component.empty()) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        if (spec.axis == UiAxis.HORIZONTAL) {
            val nodeY = y + 6
            val usable = (width - 10).coerceAtLeast(0)
            spec.steps.forEachIndexed { index, step ->
                val centerX = x + 5 + if (spec.steps.size == 1) usable / 2 else usable * index / (spec.steps.size - 1)
                if (index < spec.steps.lastIndex) {
                    val nextX = x + 5 + usable * (index + 1) / (spec.steps.size - 1)
                    graphics.fill(centerX + 4, nodeY - 1, nextX - 4, nodeY + 1, theme.colors.border)
                }
                drawStepNode(graphics, centerX, nodeY, step.state)
                if (spec.showLabels) {
                    val labelWidth = font.width(step.label)
                    graphics.drawString(font, step.label, centerX - labelWidth / 2, nodeY + 8, theme.colors.textSecondary, false)
                }
            }
        } else {
            val spacing = if (spec.steps.size == 1) 0 else (height - 10) / (spec.steps.size - 1)
            spec.steps.forEachIndexed { index, step ->
                val centerY = y + 5 + spacing * index
                if (index < spec.steps.lastIndex) {
                    graphics.fill(x + 4, centerY + 4, x + 6, centerY + spacing - 4, theme.colors.border)
                }
                drawStepNode(graphics, x + 5, centerY, step.state)
                if (spec.showLabels) graphics.drawString(font, step.label, x + 14, centerY - font.lineHeight / 2, theme.colors.textSecondary, false)
            }
        }
    }

    private fun drawStepNode(graphics: GuiGraphics, centerX: Int, centerY: Int, state: UiStepState) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val fill = when (state) {
            UiStepState.LOCKED -> theme.colors.textDim
            UiStepState.AVAILABLE -> theme.colors.accentPrimary
            UiStepState.CLEARED -> theme.colors.accentGood
            UiStepState.ACTIVE -> theme.colors.accentCaution
        }
        UiSurfaceRenderer.draw(
            graphics,
            centerX - 5,
            centerY - 5,
            10,
            10,
            UiSurfaceStyle(UiShape.Circle, UiFill.Solid(fill), UiBorder.Solid(theme.colors.borderBright))
        )
        val marker = theme.colors.border
        when (state) {
            UiStepState.LOCKED -> {
                graphics.fill(centerX - 2, centerY - 2, centerX - 1, centerY + 2, marker)
                graphics.fill(centerX + 1, centerY - 2, centerX + 2, centerY + 2, marker)
            }
            UiStepState.AVAILABLE -> graphics.fill(centerX - 2, centerY - 1, centerX + 3, centerY + 1, marker)
            UiStepState.CLEARED -> {
                graphics.fill(centerX - 2, centerY, centerX, centerY + 2, marker)
                graphics.fill(centerX, centerY - 2, centerX + 2, centerY + 1, marker)
            }
            UiStepState.ACTIVE -> {
                graphics.fill(centerX, centerY - 2, centerX + 1, centerY + 3, marker)
                graphics.fill(centerX - 2, centerY, centerX + 3, centerY + 1, marker)
            }
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        val narration = Component.empty()
        spec.steps.forEachIndexed { index, step ->
            if (index > 0) narration.append(Component.literal(". "))
            narration.append(step.label).append(Component.literal(": ${step.state.name.lowercase()}"))
        }
        output.add(NarratedElementType.TITLE, narration)
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiStepTrackSpec): CobblemonUiStepTrack {
            require(width > 0) { "Step track width must be positive" }
            val height = if (spec.axis == UiAxis.HORIZONTAL) 26 else maxOf(24, spec.steps.size * 22)
            return CobblemonUiStepTrack(x, y, width, height, spec)
        }
    }
}

class CobblemonUiOrderedChoice private constructor(
    x: Int,
    y: Int,
    width: Int,
    private val optionId: String,
    private val state: UiOrderedSelectionState,
    private val changed: (List<String>) -> Unit
) : AbstractButton(x, y, width, 24, state.options.first { it.id == optionId }.label) {
    private val option get() = state.options.first { it.id == optionId }

    init {
        active = option.enabled
    }

    override fun onPress() {
        if (state.toggle(optionId)) changed(state.selectedIds)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val changedOrder = when (keyCode) {
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> state.moveEarlier(optionId)
            GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN -> state.moveLater(optionId)
            else -> false
        }
        if (changedOrder) {
            changed(state.selectedIds)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val order = state.orderOf(optionId)
        val widgetState = when {
            !active -> UiWidgetState.DISABLED
            isFocused -> UiWidgetState.FOCUS
            isHovered -> UiWidgetState.HOVER
            order != null -> UiWidgetState.SELECTED
            else -> UiWidgetState.NORMAL
        }
        val style = theme.style(UiButtonVariant.SECONDARY, widgetState)
        UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
        val font = Minecraft.getInstance().font
        graphics.drawString(font, option.label, x + 7, y + (height - font.lineHeight) / 2, style.text, style.textShadow)
        order?.let {
            val marker = Component.literal(it.toString())
            UiSurfaceRenderer.draw(
                graphics,
                x + width - 19,
                y + 5,
                14,
                14,
                UiSurfaceStyle(UiShape.Circle, UiFill.Solid(theme.colors.accentPrimary), UiBorder.Solid(theme.colors.borderBright))
            )
            graphics.drawString(font, marker, x + width - 12 - font.width(marker) / 2, y + 8, theme.colors.textPrimary, false)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    companion object {
        fun create(
            x: Int,
            y: Int,
            width: Int,
            optionId: String,
            state: UiOrderedSelectionState,
            changed: (List<String>) -> Unit = {}
        ): CobblemonUiOrderedChoice {
            require(width > 0) { "Ordered choice width must be positive" }
            require(state.options.any { it.id == optionId }) { "Unknown ordered choice id: $optionId" }
            return CobblemonUiOrderedChoice(x, y, width, optionId, state, changed)
        }
    }
}

object CobblemonUiOrderedSelectionGroup {
    fun createButtons(
        x: Int,
        y: Int,
        width: Int,
        state: UiOrderedSelectionState,
        verticalGap: Int = 3,
        changed: (List<String>) -> Unit = {}
    ): List<CobblemonUiOrderedChoice> = state.options.mapIndexed { index, option ->
        CobblemonUiOrderedChoice.create(x, y + index * (24 + verticalGap), width, option.id, state, changed)
    }
}

private fun textColor(tone: UiTextTone): Int {
    val colors = CobblemonUiThemes.registry.snapshot().colors
    return when (tone) {
        UiTextTone.PRIMARY -> colors.textPrimary
        UiTextTone.SECONDARY -> colors.textSecondary
        UiTextTone.MUTED -> colors.textDim
        UiTextTone.SUCCESS -> colors.accentGood
        UiTextTone.WARNING -> colors.accentCaution
        UiTextTone.DANGER -> colors.accentDanger
        UiTextTone.PANEL -> CobblemonUiThemes.registry.snapshot().surfaces.panelText ?: colors.textPrimary
        UiTextTone.PANEL_ALT -> CobblemonUiThemes.registry.snapshot().surfaces.panelAltText ?: colors.textPrimary
    }
}

private fun toneColor(tone: UiOverlayTone): Int {
    val colors = CobblemonUiThemes.registry.snapshot().colors
    return when (tone) {
        UiOverlayTone.NEUTRAL -> colors.borderBright
        UiOverlayTone.INFO -> colors.accentPrimary
        UiOverlayTone.SUCCESS -> colors.accentGood
        UiOverlayTone.WARNING -> colors.accentCaution
        UiOverlayTone.DANGER -> colors.accentDanger
    }
}
