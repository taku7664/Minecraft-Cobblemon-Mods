package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
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
 * The room's settings as short chips beside one shared label column, instead of the hub's full-height buttons: one
 * line per setting, a setting whose chips do not fit one line wrapping them onto a second.
 */
internal object PvpRoomSettingRows {
    const val CHIP_HEIGHT = 14
    private const val CHIP_GAP = 2
    private const val ROW_GAP = 4

    class Option(val id: String, val label: Component)

    class Row(
        val title: Component,
        val options: List<Option>,
        val selected: Set<String>,
        val enabled: Boolean,
        val tooltip: Component? = null,
        val press: (String) -> Unit,
    )

    /** Lays [rows] down [rect] and returns the y just below the last one. */
    fun place(host: MccHubContentHost, rect: UiRect, rows: List<Row>): Int {
        val font = Minecraft.getInstance().font
        val labelWidth = rows.maxOf { font.width(it.title) } + 6
        val chipsX = rect.x + labelWidth
        val chipsWidth = (rect.right - chipsX).coerceAtLeast(1)
        var y = rect.y
        rows.forEach { row ->
            val perLine = perLine(chipsWidth, row.options.map { font.width(it.label) })
            val lines = row.options.chunked(perLine)
            host.add(Label(UiRect(rect.x, y, labelWidth, CHIP_HEIGHT), row.title))
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
            graphics.drawString(font, MccHubKit.fitted(text, rect.width - 4), rect.x, rect.y + (rect.height - font.lineHeight) / 2 + 1,
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
                isHovered || isFocused -> UiWidgetState.HOVER
                selected -> UiWidgetState.SELECTED
                else -> UiWidgetState.NORMAL
            }
            val style = theme.style(UiButtonVariant.SECONDARY, state)
            UiSurfaceRenderer.draw(graphics, x, y, width, height, style.surface)
            val font = Minecraft.getInstance().font
            val text = MccHubKit.fitted(message, width - 6)
            UiTextRenderer.draw(graphics, font, text, x + (width - font.width(text)) / 2, y + (height - font.lineHeight) / 2 + 1,
                style.text, style.textShadowColor, style.textShadow)
            val chosen = if (selected && state != UiWidgetState.DISABLED) theme.style(UiButtonVariant.SECONDARY, UiWidgetState.SELECTED).selectionIndicator else null
            UiSurfaceRenderer.drawSelection(graphics, x, y, width, height, style.surface.shape,
                UiSurfaceRenderer.indicatorFor(style.selectionIndicator, chosen, selected))
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
    }
}

/** A floating card naming who is watching, drawn over everything else at the mouse. */
internal object PvpSpectatorCard {
    private const val PADDING = 4
    private const val ROW = 11
    private const val BIG_FACE = 32

    /** One spectator: a large face and the name under it. */
    fun drawOne(graphics: GuiGraphics, mouseX: Int, mouseY: Int, member: PvpRoomMemberView) {
        val font = Minecraft.getInstance().font
        val width = maxOf(BIG_FACE, font.width(member.name)) + PADDING * 2
        val height = PADDING + BIG_FACE + 3 + font.lineHeight + PADDING
        draw(graphics, mouseX, mouseY, width, height) { x, y ->
            face(graphics, member, x + (width - BIG_FACE) / 2, y + PADDING, BIG_FACE)
            graphics.drawString(font, member.name, x + (width - font.width(member.name)) / 2, y + PADDING + BIG_FACE + 3, TEXT, false)
        }
    }

    /** Everyone watching, a small face before each name, under [heading]. */
    fun drawList(graphics: GuiGraphics, mouseX: Int, mouseY: Int, heading: Component, members: List<PvpRoomMemberView>, more: Component?) {
        val font = Minecraft.getInstance().font
        val width = (listOf(font.width(heading), more?.let(font::width) ?: 0) + members.map { font.width(it.name) + 11 }).max() + PADDING * 2
        val height = PADDING * 2 + ROW * (1 + members.size + if (more != null) 1 else 0)
        draw(graphics, mouseX, mouseY, width, height) { x, y ->
            var line = y + PADDING
            graphics.drawString(font, heading, x + PADDING, line + 1, DIM, false)
            members.forEach { member ->
                line += ROW
                face(graphics, member, x + PADDING, line, 8)
                graphics.drawString(font, member.name, x + PADDING + 11, line + 1, TEXT, false)
            }
            more?.let { graphics.drawString(font, it, x + PADDING, line + ROW + 1, DIM, false) }
        }
    }

    fun face(graphics: GuiGraphics, member: PvpRoomMemberView, x: Int, y: Int, size: Int) {
        val profile = Minecraft.getInstance().connection?.getPlayerInfo(member.playerId)?.profile ?: return
        CobblemonUiRenderSlot.drawContent(graphics, UiRect(x, y, size, size), CobblemonUiRenderContent.PlayerFace(profile), 0f)
    }

    private inline fun draw(graphics: GuiGraphics, mouseX: Int, mouseY: Int, width: Int, height: Int, content: (Int, Int) -> Unit) {
        val x = (mouseX + 10).let { if (it + width > graphics.guiWidth() - 2) mouseX - 10 - width else it }.coerceAtLeast(2)
        val y = (mouseY - 6).coerceIn(2, (graphics.guiHeight() - height - 2).coerceAtLeast(2))
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 400f)
        graphics.fill(x, y, x + width, y + height, BACKGROUND)
        graphics.renderOutline(x, y, width, height, BORDER)
        content(x, y)
        graphics.pose().popPose()
    }

    private const val BACKGROUND = 0xF0100010.toInt()
    private const val BORDER = 0xFF5028A0.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val DIM = 0xFFAAAAAA.toInt()
}
