package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBadgeSpec
import jbro.cobblemon.uikit.UiBadgeTone
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiListItemSpec
import jbro.cobblemon.uikit.UiProgressSpec
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiTabSpec
import jbro.cobblemon.uikit.UiToggleSpec
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import kotlin.math.max

object CobblemonUiTab {
    fun create(
        x: Int,
        y: Int,
        availableWidth: Int,
        spec: UiTabSpec,
        press: () -> Unit = {}
    ): CobblemonUiButton = CobblemonUiButton.create(
        x,
        y,
        availableWidth,
        UiButtonSpec(
            title = spec.label,
            icon = spec.icon,
            variant = UiButtonVariant.SECONDARY,
            size = jbro.cobblemon.uikit.UiControlSize.SMALL,
            width = spec.width,
            selected = spec.selected
        ),
        press = press
    )
}

class CobblemonUiListItem private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiListItemSpec,
    private val press: () -> Unit
) : AbstractButton(x, y, width, height, spec.title) {
    override fun onPress() = press()

    override fun createNarrationMessage(): MutableComponent {
        val message = Component.empty().append(spec.title)
        spec.supportingText?.let { message.append(Component.literal(". ")).append(it) }
        spec.trailingText?.let { message.append(Component.literal(". ")).append(it) }
        return wrapDefaultNarrationMessage(message)
    }

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
        UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)

        val font = Minecraft.getInstance().font
        val metrics = theme.metrics(jbro.cobblemon.uikit.UiControlSize.MEDIUM)
        val padding = metrics.horizontalPadding.coerceAtLeast(6)
        val iconSize = minOf(metrics.iconSize, PIXEL_SPRITE_SIZE)
        var contentLeft = x + padding
        spec.icon?.let {
            drawIcon(graphics, it, contentLeft, y + (height - iconSize) / 2, iconSize)
            contentLeft += iconSize + metrics.iconGap
        }
        val titleTop = if (spec.supportingText == null) y + (height - font.lineHeight) / 2 else y + 5
        graphics.drawString(font, spec.title, contentLeft, titleTop, style.text, style.textShadow)
        spec.supportingText?.let {
            graphics.drawString(font, it, contentLeft, y + height - font.lineHeight - 4, style.supportingText, false)
        }
        spec.trailingText?.let {
            graphics.drawString(font, it, x + width - padding - font.width(it), y + (height - font.lineHeight) / 2, style.supportingText, false)
        }
    }

    companion object {
        private const val PIXEL_SPRITE_SIZE = 8

        fun create(
            x: Int,
            y: Int,
            availableWidth: Int,
            spec: UiListItemSpec,
            press: () -> Unit = {}
        ): CobblemonUiListItem {
            val theme = CobblemonUiThemes.registry.snapshot()
            val metrics = theme.metrics(jbro.cobblemon.uikit.UiControlSize.MEDIUM)
            val font = Minecraft.getInstance().font
            val iconWidth = if (spec.icon == null) 0 else minOf(metrics.iconSize, PIXEL_SPRITE_SIZE) + metrics.iconGap
            val trailingWidth = spec.trailingText?.let(font::width) ?: 0
            val naturalWidth = font.width(spec.title) + iconWidth + trailingWidth + metrics.horizontalPadding * 2 + if (trailingWidth > 0) 12 else 0
            val width = resolveWidth(spec.width, naturalWidth, availableWidth)
            val height = if (spec.supportingText == null) metrics.height else metrics.supportingHeight
            return CobblemonUiListItem(x, y, width, height, spec, press)
        }
    }
}

class CobblemonUiBadge private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiBadgeSpec
) : AbstractWidget(x, y, width, height, spec.label) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val color = when (spec.tone) {
            UiBadgeTone.NEUTRAL -> theme.colors.panelAlt
            UiBadgeTone.INFO -> theme.colors.accentPrimary
            UiBadgeTone.SUCCESS -> theme.colors.accentGood
            UiBadgeTone.WARNING -> theme.colors.accentCaution
            UiBadgeTone.DANGER -> theme.colors.accentDanger
        }
        val textColor = if (spec.tone == UiBadgeTone.WARNING) theme.colors.shell else theme.colors.textPrimary
        UiSurfaceRenderer.draw(
            graphics,
            x,
            y,
            width,
            height,
            UiSurfaceStyle(UiShape.Capsule, UiFill.Solid(color), UiBorder.Solid(theme.colors.border))
        )
        val font = Minecraft.getInstance().font
        var contentLeft = x + 6
        spec.icon?.let {
            drawIcon(graphics, it, contentLeft, y + (height - 8) / 2, 8)
            contentLeft += 11
        }
        graphics.drawString(
            font,
            spec.label,
            contentLeft,
            y + (height - font.lineHeight) / 2 + PIXEL_FONT_OPTICAL_OFFSET_Y,
            textColor,
            false
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, spec.label)
    }

    companion object {
        fun create(x: Int, y: Int, spec: UiBadgeSpec): CobblemonUiBadge {
            val font = Minecraft.getInstance().font
            val width = font.width(spec.label) + 12 + if (spec.icon == null) 0 else 11
            return CobblemonUiBadge(x, y, width, 16, spec)
        }
    }
}

class CobblemonUiToggle private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiToggleSpec,
    initialValue: Boolean,
    private val changed: (Boolean) -> Unit
) : AbstractButton(x, y, width, height, spec.label) {
    var value: Boolean = initialValue
        private set

    override fun onPress() {
        value = !value
        changed(value)
    }

    override fun createNarrationMessage(): MutableComponent = wrapDefaultNarrationMessage(
        Component.empty().append(spec.label).append(Component.literal(": ")).append(
            Component.translatable(if (value) "options.on" else "options.off")
        )
    )

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val state = when {
            !active -> UiWidgetState.DISABLED
            isFocused -> UiWidgetState.FOCUS
            isHovered -> UiWidgetState.HOVER
            value -> UiWidgetState.SELECTED
            else -> UiWidgetState.NORMAL
        }
        val style = theme.style(UiButtonVariant.SECONDARY, state)
        UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
        val font = Minecraft.getInstance().font
        val padding = 8
        graphics.drawString(
            font,
            spec.label,
            x + padding,
            y + (height - font.lineHeight) / 2 + PIXEL_FONT_OPTICAL_OFFSET_Y,
            style.text,
            false
        )

        val trackWidth = 24
        val trackHeight = 12
        val trackLeft = x + width - padding - trackWidth
        val trackTop = y + (height - trackHeight) / 2
        UiSurfaceRenderer.draw(
            graphics,
            trackLeft,
            trackTop,
            trackWidth,
            trackHeight,
            UiSurfaceStyle(
                UiShape.Capsule,
                UiFill.Solid(if (value) theme.colors.accentGood else theme.colors.panelAlt),
                UiBorder.Solid(theme.colors.border)
            )
        )
        val knobSize = 8
        val knobLeft = if (value) trackLeft + trackWidth - knobSize - 2 else trackLeft + 2
        UiSurfaceRenderer.draw(
            graphics,
            knobLeft,
            trackTop + 2,
            knobSize,
            knobSize,
            UiSurfaceStyle(UiShape.Circle, UiFill.Solid(theme.colors.textPrimary), UiBorder.None)
        )
    }

    companion object {
        fun create(
            x: Int,
            y: Int,
            availableWidth: Int,
            spec: UiToggleSpec,
            changed: (Boolean) -> Unit = {}
        ): CobblemonUiToggle {
            val theme = CobblemonUiThemes.registry.snapshot()
            val metrics = theme.metrics(spec.size)
            val naturalWidth = Minecraft.getInstance().font.width(spec.label) + 24 + metrics.horizontalPadding * 2 + 8
            val width = resolveWidth(spec.width, naturalWidth, availableWidth)
            return CobblemonUiToggle(x, y, width, metrics.height, spec, spec.value, changed)
        }
    }
}

class CobblemonUiProgressBar private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiProgressSpec
) : AbstractWidget(x, y, width, height, spec.label ?: Component.empty()) {
    init {
        active = false
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val labelHeight = if (spec.label == null && !spec.showValue) 0 else 12
        val barTop = y + labelHeight
        val barHeight = height - labelHeight
        UiSurfaceRenderer.draw(
            graphics,
            x,
            barTop,
            width,
            barHeight,
            theme.surfaces.panelAlt.copy(shape = UiShape.Capsule, border = UiBorder.Solid(theme.colors.border))
        )
        val fillWidth = ((width - 2) * spec.fraction).toInt()
        if (fillWidth > 0) {
            UiSurfaceRenderer.draw(
                graphics,
                x + 1,
                barTop + 1,
                fillWidth,
                max(1, barHeight - 2),
                UiSurfaceStyle(
                    UiShape.Capsule,
                    UiFill.VerticalGradient(theme.colors.accentGood, theme.colors.accentPrimary),
                    UiBorder.None
                )
            )
        }
        val font = Minecraft.getInstance().font
        spec.label?.let { graphics.drawString(font, it, x, y, theme.colors.textSecondary, false) }
        if (spec.showValue) {
            val value = Component.literal("${spec.value}/${spec.maximum}")
            graphics.drawString(font, value, x + width - font.width(value), y, theme.colors.textSecondary, false)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        val narration = Component.empty()
        spec.label?.let { narration.append(it).append(Component.literal(": ")) }
        narration.append(Component.literal("${spec.value}/${spec.maximum}"))
        output.add(NarratedElementType.TITLE, narration)
    }

    companion object {
        fun create(x: Int, y: Int, width: Int, spec: UiProgressSpec): CobblemonUiProgressBar {
            require(width > 0) { "Progress width must be positive" }
            val height = if (spec.label == null && !spec.showValue) 8 else 20
            return CobblemonUiProgressBar(x, y, width, height, spec)
        }
    }
}

private fun drawIcon(graphics: GuiGraphics, icon: UiIcon, left: Int, top: Int, size: Int) {
    graphics.blit(
        ResourceLocation.fromNamespaceAndPath(icon.namespace, icon.path),
        left,
        top,
        0f,
        0f,
        size,
        size,
        8,
        8
    )
}

private fun resolveWidth(policy: UiWidthPolicy, naturalWidth: Int, availableWidth: Int): Int = when (policy) {
    UiWidthPolicy.Content -> naturalWidth.coerceAtMost(availableWidth)
    UiWidthPolicy.Fill -> availableWidth
    is UiWidthPolicy.Fixed -> policy.pixels.coerceAtMost(availableWidth)
}

private const val PIXEL_FONT_OPTICAL_OFFSET_Y = 1
