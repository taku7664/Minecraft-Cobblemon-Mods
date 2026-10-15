package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * Settings as short chips beside one shared label column, instead of the hub's full-height buttons: one line per
 * setting, a setting whose chips do not fit one line wrapping them onto a second. A row with one choice marks one
 * chip; a pick-any row marks each chosen one.
 */
object MccHubChipRows {
    const val CHIP_HEIGHT = 14
    private const val CHIP_GAP = 2
    private const val ROW_GAP = 4
    private const val LABEL_LINE = 11

    class Option(val id: String, val label: Component)

    class Row(
        val title: Component,
        val options: List<Option>,
        val selected: Set<String>,
        val enabled: Boolean,
        val tooltip: Component? = null,
        val press: (String) -> Unit,
    )

    /**
     * Lays [rows] down [rect] and returns the y just below the last one. When the label column leaves a row of several
     * chips room for one a line, every label goes on a line of its own above its chips, which then take the full width.
     */
    fun place(host: MccHubContentHost, rect: UiRect, rows: List<Row>): Int {
        val font = Minecraft.getInstance().font
        val labelWidth = rows.maxOf { font.width(it.title) } + 6
        val besideWidth = (rect.right - rect.x - labelWidth).coerceAtLeast(1)
        val stacked = rows.any { row -> row.options.size > 1 && perLine(besideWidth, row.options.map { font.width(it.label) }) == 1 }
        val chipsX = if (stacked) rect.x else rect.x + labelWidth
        val chipsWidth = (rect.right - chipsX).coerceAtLeast(1)
        var y = rect.y
        rows.forEach { row ->
            if (stacked) {
                host.add(Label(UiRect(rect.x, y, rect.width, LABEL_LINE), row.title))
                y += LABEL_LINE
            } else {
                host.add(Label(UiRect(rect.x, y, labelWidth, CHIP_HEIGHT), row.title))
            }
            val perLine = perLine(chipsWidth, row.options.map { font.width(it.label) })
            val lines = row.options.chunked(perLine)
            lines.forEachIndexed { lineIndex, line ->
                val chipWidth = (chipsWidth - (perLine - 1) * CHIP_GAP) / perLine
                line.forEachIndexed { index, option ->
                    val chip = Chip(chipsX + index * (chipWidth + CHIP_GAP), y + lineIndex * (CHIP_HEIGHT + CHIP_GAP), chipWidth,
                        option.label, option.id in row.selected) { row.press(option.id) }
                    chip.active = row.enabled
                    row.tooltip?.let { chip.setTooltip(Tooltip.create(it)) }
                    host.add(chip)
                }
            }
            y += lines.size * CHIP_HEIGHT + (lines.size - 1) * CHIP_GAP + ROW_GAP
        }
        return y - ROW_GAP
    }

    /** How many chips share a line: all of them when each still has room for its label, else as few lines as fit. */
    fun perLine(width: Int, labelWidths: List<Int>): Int {
        val needed = labelWidths.maxOrNull()?.plus(8) ?: return 1
        for (count in labelWidths.size downTo 1) {
            if ((width - (count - 1) * CHIP_GAP) / count >= needed) {
                // Two lines of three read better than five and one.
                val lines = (labelWidths.size + count - 1) / count
                return (labelWidths.size + lines - 1) / lines
            }
        }
        return 1
    }

    private class Label(private val rect: UiRect, private val text: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val line = MccHubKit.fitted(text, rect.width - 4)
            graphics.drawString(font, line, rect.x, UiTextRenderer.centeredY(font, line, rect.y, rect.height),
                MccHubKit.panelText(CobblemonUiThemes.registry.snapshot()), false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** A short toggle drawn in the theme's secondary button style, its selection marked as the theme marks it. */
    private class Chip(x: Int, y: Int, width: Int, label: Component, private val selected: Boolean, private val press: () -> Unit) :
        AbstractButton(x, y, width, CHIP_HEIGHT, label) {
        override fun onPress() {
            if (active) press()
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val state = when {
                !active -> UiWidgetState.DISABLED
                selected -> UiWidgetState.SELECTED
                isHovered || isFocused -> UiWidgetState.HOVER
                else -> UiWidgetState.NORMAL
            }
            val style = theme.style(UiButtonVariant.SECONDARY, state)
            UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
            val font = Minecraft.getInstance().font
            val text = MccHubKit.fitted(message, width - 6)
            UiTextRenderer.draw(graphics, font, text, x + (width - font.width(text)) / 2, UiTextRenderer.centeredY(font, text, y, height),
                style.text, style.textShadowColor, style.textShadow)
            val chosen = if (selected && state != UiWidgetState.DISABLED) theme.style(UiButtonVariant.SECONDARY, UiWidgetState.SELECTED).selectionIndicator else null
            UiSurfaceRenderer.drawSelection(graphics, x, y, width, height, style.surface.shape,
                UiSurfaceRenderer.indicatorFor(style.selectionIndicator, chosen, selected))
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
    }
}
