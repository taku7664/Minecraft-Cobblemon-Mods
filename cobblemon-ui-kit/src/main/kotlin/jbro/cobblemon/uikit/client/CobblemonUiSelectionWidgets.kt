package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiCheckboxSpec
import jbro.cobblemon.uikit.UiComboBoxSpec
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiRadioSpec
import jbro.cobblemon.uikit.UiSelectionState
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

class CobblemonUiCheckbox private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiCheckboxSpec,
    private val changed: (Boolean) -> Unit
) : AbstractButton(x, y, width, height, spec.label) {
    var checked: Boolean = spec.checked
        private set

    init {
        active = spec.enabled
    }

    override fun onPress() {
        checked = !checked
        changed(checked)
    }

    override fun createNarrationMessage(): MutableComponent = wrapDefaultNarrationMessage(
        Component.empty().append(spec.label).append(Component.literal(": ")).append(
            Component.translatable(if (checked) "options.on" else "options.off")
        )
    )

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val state = widgetState(active, isFocused, isHovered, checked)
        val style = theme.style(UiButtonVariant.GHOST, state)
        val boxSize = 12
        val boxTop = y + (height - boxSize) / 2
        UiSurfaceRenderer.draw(
            graphics,
            x,
            boxTop,
            boxSize,
            boxSize,
            UiSurfaceStyle(
                UiShape.RoundedRectangle(2),
                UiFill.Solid(if (checked) theme.colors.accentGood else theme.colors.panel),
                UiBorder.Solid(if (isFocused || isHovered) theme.colors.borderBright else theme.colors.border)
            )
        )
        if (checked) drawCheck(graphics, x + 3, boxTop + 3, theme.colors.textPrimary)
        graphics.drawString(
            Minecraft.getInstance().font,
            spec.label,
            x + boxSize + 5,
            y + (height - Minecraft.getInstance().font.lineHeight) / 2 + 1,
            style.text,
            false
        )
    }

    companion object {
        fun create(x: Int, y: Int, spec: UiCheckboxSpec, changed: (Boolean) -> Unit = {}): CobblemonUiCheckbox {
            val font = Minecraft.getInstance().font
            return CobblemonUiCheckbox(x, y, font.width(spec.label) + 17, 18, spec, changed)
        }
    }
}

class CobblemonUiRadioButton private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiRadioSpec,
    private val selected: () -> Boolean,
    private val choose: () -> Unit
) : AbstractButton(x, y, width, height, spec.option.label) {
    override fun onPress() = choose()
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val selectedNow = selected()
        val size = 12
        val top = y + (height - size) / 2
        UiSurfaceRenderer.draw(
            graphics,
            x,
            top,
            size,
            size,
            UiSurfaceStyle(UiShape.Circle, UiFill.Solid(theme.colors.panel), UiBorder.Solid(theme.colors.border))
        )
        if (selectedNow) {
            UiSurfaceRenderer.draw(
                graphics,
                x + 3,
                top + 3,
                6,
                6,
                UiSurfaceStyle(UiShape.Circle, UiFill.Solid(theme.colors.accentPrimary), UiBorder.None)
            )
        }
        graphics.drawString(
            Minecraft.getInstance().font,
            spec.option.label,
            x + size + 5,
            y + (height - Minecraft.getInstance().font.lineHeight) / 2 + 1,
            theme.colors.textPrimary,
            false
        )
    }

    companion object {
        fun create(
            x: Int,
            y: Int,
            spec: UiRadioSpec,
            selected: () -> Boolean,
            choose: () -> Unit
        ): CobblemonUiRadioButton {
            val width = Minecraft.getInstance().font.width(spec.option.label) + 17
            return CobblemonUiRadioButton(x, y, width, 18, spec, selected, choose)
        }
    }
}

class CobblemonUiRadioGroup(
    options: List<jbro.cobblemon.uikit.UiChoiceOption>,
    selectedIndex: Int = 0,
    private val changed: (jbro.cobblemon.uikit.UiChoiceOption) -> Unit = {}
) {
    val state = UiSelectionState(options, selectedIndex)

    fun createButtons(x: Int, y: Int, verticalGap: Int = 2): List<CobblemonUiRadioButton> {
        var cursorY = y
        return state.options.mapIndexed { index, option ->
            val button = CobblemonUiRadioButton.create(
                x,
                cursorY,
                UiRadioSpec(option, index == state.selectedIndex),
                selected = { state.selectedIndex == index },
                choose = {
                    if (state.select(index)) changed(state.selected)
                }
            )
            button.active = option.enabled
            cursorY += button.height + verticalGap
            button
        }
    }
}

class CobblemonUiComboBox private constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    val spec: UiComboBoxSpec,
    private val changed: (jbro.cobblemon.uikit.UiChoiceOption) -> Unit
) : AbstractButton(x, y, width, height, spec.label) {
    val state = UiSelectionState(spec.options, spec.selectedIndex)
    var expanded: Boolean = false
        private set

    override fun onPress() {
        expanded = !expanded
    }

    override fun createNarrationMessage(): MutableComponent = wrapDefaultNarrationMessage(
        Component.empty().append(spec.label).append(Component.literal(": ")).append(state.selected.label)
    )

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val style = theme.style(UiButtonVariant.SECONDARY, widgetState(active, isFocused, isHovered, expanded))
        UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
        val font = Minecraft.getInstance().font
        graphics.drawString(font, state.selected.label, x + 7, y + (height - font.lineHeight) / 2 + 1, style.text, false)
        graphics.drawString(font, if (expanded) "▲" else "▼", x + width - 13, y + (height - font.lineHeight) / 2, style.text, false)
        if (expanded) renderOptions(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (expanded && button == 0) {
            val index = optionAt(mouseX, mouseY)
            if (index != null) {
                if (state.select(index)) changed(state.selected)
                expanded = false
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun renderOptions(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        spec.options.forEachIndexed { index, option ->
            val top = y + height + 2 + index * OPTION_HEIGHT
            val hover = mouseX in x until x + width && mouseY in top until top + OPTION_HEIGHT
            val state = when {
                !option.enabled -> UiWidgetState.DISABLED
                index == state.selectedIndex -> UiWidgetState.SELECTED
                hover -> UiWidgetState.HOVER
                else -> UiWidgetState.NORMAL
            }
            val style = theme.style(UiButtonVariant.SECONDARY, state)
            UiSurfaceRenderer.draw(graphics, x, top, width, OPTION_HEIGHT, style.surface)
            graphics.drawString(font, option.label, x + 7, top + (OPTION_HEIGHT - font.lineHeight) / 2 + 1, style.text, false)
        }
    }

    private fun optionAt(mouseX: Double, mouseY: Double): Int? {
        if (mouseX < x || mouseX >= x + width || mouseY < y + height + 2) return null
        val index = ((mouseY - y - height - 2) / OPTION_HEIGHT).toInt()
        return index.takeIf { it in spec.options.indices }
    }

    companion object {
        private const val OPTION_HEIGHT = 20

        fun create(
            x: Int,
            y: Int,
            availableWidth: Int,
            spec: UiComboBoxSpec,
            changed: (jbro.cobblemon.uikit.UiChoiceOption) -> Unit = {}
        ): CobblemonUiComboBox {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val natural = (spec.options.maxOfOrNull { font.width(it.label) } ?: 0) + 26
            val width = when (val policy = spec.width) {
                UiWidthPolicy.Content -> natural.coerceAtMost(availableWidth)
                UiWidthPolicy.Fill -> availableWidth
                is UiWidthPolicy.Fixed -> policy.pixels.coerceAtMost(availableWidth)
            }
            return CobblemonUiComboBox(x, y, width, theme.metrics(UiControlSize.MEDIUM).height, spec, changed)
        }
    }
}

private fun widgetState(active: Boolean, focused: Boolean, hovered: Boolean, selected: Boolean): UiWidgetState = when {
    !active -> UiWidgetState.DISABLED
    focused -> UiWidgetState.FOCUS
    hovered -> UiWidgetState.HOVER
    selected -> UiWidgetState.SELECTED
    else -> UiWidgetState.NORMAL
}

private fun drawCheck(graphics: GuiGraphics, x: Int, y: Int, color: Int) {
    graphics.fill(x, y + 3, x + 2, y + 5, color)
    graphics.fill(x + 2, y + 4, x + 4, y + 6, color)
    graphics.fill(x + 4, y + 1, x + 6, y + 5, color)
}
