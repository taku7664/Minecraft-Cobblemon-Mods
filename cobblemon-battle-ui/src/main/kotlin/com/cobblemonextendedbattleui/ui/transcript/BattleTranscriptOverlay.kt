package jbro.cobblemon.battleui.extended.ui.transcript

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.extended.*
import jbro.cobblemon.battleui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurface
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.battleui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.battleui.transcript.TranscriptPolicy
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Read-only modal history. Opening it never advances dialogue or sends a battle response. */
object BattleTranscriptOverlay {
    var isOpen = false
        private set
    private var toggleHeld = false
    private var scroll = 0
    private var maxScroll = 0
    private var followBottom = true
    private var cacheKey = ""
    private var rows = emptyList<Row>()
    private var totalHeight = 0
    private var closeLeft = 0
    private var closeTop = 0
    private var closeRight = 0
    private var closeBottom = 0
    private var viewportHeight = 0

    private data class Line(val text: OrderedText, val color: Int, val result: Boolean)
    private data class Row(val turn: Int, val speaker: TranscriptSpeaker?, val lines: List<Line>, val height: Int, val separator: Boolean = false)

    fun clear() {
        isOpen = false
        toggleHeld = false
        scroll = 0
        maxScroll = 0
        followBottom = true
        cacheKey = ""
        rows = emptyList()
        TranscriptPortraits.clear()
    }

    fun close() { isOpen = false }

    fun updateVisibility() {
        if (isOpen && (CobblemonClient.battle?.minimised != false ||
                MinecraftClient.getInstance().currentScreen !is com.cobblemon.mod.common.client.gui.battle.BattleGUI)) close()
    }

    fun keyPressed(keyCode: Int, scanCode: Int): Boolean {
        if (CobblemonExtendedBattleUIClient.toggleLogKey.matchesKey(keyCode, scanCode)) {
            if (!toggleHeld) {
                toggleHeld = true
                if (isOpen) close() else if (CobblemonClient.battle?.minimised == false) {
                    if (BattleInfoPanel.isExpanded) BattleInfoPanel.toggle()
                    isOpen = true
                    followBottom = true
                    MoveTooltipRenderer.suspendForModal()
                }
            }
            return true
        }
        if (!isOpen) return false
        when {
            keyCode == GLFW.GLFW_KEY_ESCAPE || CobblemonExtendedBattleUIClient.cancelActionKey.matchesKey(keyCode, scanCode) -> close()
            keyCode == GLFW.GLFW_KEY_UP -> scrollBy(-24)
            keyCode == GLFW.GLFW_KEY_DOWN -> scrollBy(24)
            keyCode == GLFW.GLFW_KEY_PAGE_UP -> scrollBy(-viewportHeight)
            keyCode == GLFW.GLFW_KEY_PAGE_DOWN -> scrollBy(viewportHeight)
            keyCode == GLFW.GLFW_KEY_HOME -> { scroll = 0; followBottom = false }
            keyCode == GLFW.GLFW_KEY_END -> { scroll = maxScroll; followBottom = true }
        }
        return true
    }

    fun releaseKey(keyCode: Int, scanCode: Int) {
        if (CobblemonExtendedBattleUIClient.toggleLogKey.matchesKey(keyCode, scanCode)) toggleHeld = false
    }

    fun scrollBy(amount: Int) {
        scroll = (scroll + amount).coerceIn(0, maxScroll)
        followBottom = scroll >= maxScroll
    }

    fun mouseClicked(x: Double, y: Double): Boolean {
        if (!isOpen) return false
        if (x >= closeLeft && x < closeRight && y >= closeTop && y < closeBottom) close()
        return true
    }

    fun render(context: DrawContext) {
        if (!isOpen) return
        val mc = MinecraftClient.getInstance()
        if (CobblemonClient.battle?.minimised != false) { close(); return }
        if (mc.options.hudHidden) return
        MoveTooltipRenderer.suspendForModal()
        renderEntries(context, BattleLog.getEntries(), "${BattleLog.revision}")
    }

    internal fun renderEntries(context: DrawContext, entries: List<BattleLog.LogEntry>, identity: String) {
        val mc = MinecraftClient.getInstance()
        val width = minOf(460, mc.window.scaledWidth - 24).coerceAtLeast(120)
        val height = minOf(360, mc.window.scaledHeight - 40).coerceAtLeast(80)
        val x = (mc.window.scaledWidth - width) / 2
        val y = (mc.window.scaledHeight - height) / 2 + 5
        val scale = PixelTextLayout.fontScale(PanelConfig.logFontScale, 1f, mc.window.scaleFactor.toFloat())
        val lineHeight = ceil(mc.textRenderer.fontHeight * scale).toInt() + 4
        val inner = width - 28
        val avatar = 28
        val bubbleWidth = (inner - 78).coerceAtLeast(44)
        val titleHeight = maxOf(21, lineHeight + 8)
        val labelY = y + titleHeight / 2 + 7
        val top = labelY + lineHeight + 3
        val bottom = y + height - maxOf(29, lineHeight + 15)
        viewportHeight = (bottom - top).coerceAtLeast(1)
        val key = "$identity/$width/$scale/${mc.options.language}"
        if (key != cacheKey) {
            cacheKey = key
            rows = layout(entries, bubbleWidth, inner, scale, lineHeight)
            totalHeight = rows.sumOf { it.height }
        }
        maxScroll = (totalHeight - viewportHeight).coerceAtLeast(0)
        scroll = if (followBottom) maxScroll else scroll.coerceIn(0, maxScroll)

        context.matrices.push()
        context.matrices.translate(0f, 0f, 1100f)
        val shellCorners = BattleCornerCuts(10, 10, 10, 10)
        BattleSurfaceRenderer.draw(context, x, y + 3, width, height, BattleSurface(0x55000000, cornerCuts = shellCorners))
        BattleSurfaceRenderer.draw(context, x, y, width, height, BattleUiTheme.shell.copy(cornerCuts = shellCorners))
        val title = tr("title")
        val titleWidth = maxOf(96, ceil(mc.textRenderer.getWidth(title) * scale).toInt() + 28)
        // The title is a pill riding the window's top edge.
        BattleSurfaceRenderer.capsule(context, x + (width - titleWidth) / 2 - 1, y - titleHeight / 2 - 1,
            titleWidth + 2, titleHeight + 2, BattleUiTheme.CYAN, .55f)
        BattleSurfaceRenderer.capsule(context, x + (width - titleWidth) / 2, y - titleHeight / 2, titleWidth, titleHeight,
            BattleUiTheme.PANEL)
        drawCentered(context, title, x + width / 2, y - (lineHeight - 4) / 2, BattleUiTheme.TEXT, scale)
        drawTag(context, tr("self"), x + 12, labelY - 1, BattleUiTheme.CYAN, scale, lineHeight)
        val rightLabel = tr("opponent")
        drawTag(context, rightLabel, x + width - 12 - ceil(mc.textRenderer.getWidth(rightLabel) * scale).toInt() - 10,
            labelY - 1, BattleUiTheme.TRANSCRIPT_OPPONENT, scale, lineHeight)
        context.draw()
        context.enableScissor(x + 8, top, x + width - 8, bottom)
        var rowY = top - scroll
        for (row in rows) {
            if (rowY + row.height > top && rowY < bottom) {
                if (row.separator) {
                    val label = if (row.turn == 0) tr("start") else Text.translatable("cobblemon_battle_ui.transcript.turn", row.turn).string
                    val textW = ceil(mc.textRenderer.getWidth(label) * scale).toInt()
                    val center = x + width / 2
                    val chipWidth = textW + 14
                    val lineY = rowY + lineHeight / 2 - 1
                    BattleSurfaceRenderer.capsule(context, x + 14, lineY, center - chipWidth / 2 - 6 - x - 14, 2, BattleUiTheme.BORDER, .7f)
                    BattleSurfaceRenderer.capsule(context, center + chipWidth / 2 + 6, lineY, x + width - 14 - center - chipWidth / 2 - 6, 2, BattleUiTheme.BORDER, .7f)
                    BattleSurfaceRenderer.capsule(context, center - chipWidth / 2, rowY - 3, chipWidth, lineHeight + 2, CHIP)
                    drawCentered(context, label, center, rowY, BattleUiTheme.MUTED, scale)
                } else if (row.speaker != null) {
                    val speaker = row.speaker
                    val faceX = if (speaker.left) x + 14 else x + width - 14 - avatar
                    val boxX = if (speaker.left) faceX + avatar + 8 else faceX - 8 - bubbleWidth
                    val accent = if (speaker.left) BattleUiTheme.CYAN else BattleUiTheme.TRANSCRIPT_OPPONENT
                    // A speech bubble: round everywhere except the small corner facing its speaker.
                    val bubble = if (speaker.left) BattleCornerCuts(2, 8, 8, 8) else BattleCornerCuts(8, 2, 8, 8)
                    BattleSurfaceRenderer.draw(context, boxX, rowY, bubbleWidth, row.height - 10,
                        (if (speaker.left) BattleUiTheme.transcriptSelf else BattleUiTheme.transcriptOpponent).copy(cornerCuts = bubble))
                    BattleSurfaceRenderer.draw(context, faceX - 1, rowY - 1, avatar + 2, avatar + 2,
                        BattleSurface(accent, cornerCuts = BattleCornerCuts(8, 8, 8, 8)), .8f)
                    BattleSurfaceRenderer.draw(context, faceX, rowY, avatar, avatar,
                        BattleUiTheme.panel.copy(cornerCuts = BattleCornerCuts(7, 7, 7, 7), borderWidth = 0))
                    // Fallback remains a labelled unknown portrait, never another Pokémon or a trainer.
                    if (!TranscriptPortraits.draw(context, speaker, faceX + 2, rowY + 2, avatar - 4)) {
                        drawCentered(context, "?", faceX + avatar / 2, rowY + 9, BattleUiTheme.MUTED, scale)
                    }
                    val name = mc.textRenderer.trimToWidth(speaker.pokemonName.string, ((bubbleWidth - 18) / scale).toInt())
                    draw(context, name, boxX + 9, rowY + 7, accent, scale)
                    var textY = rowY + 9 + lineHeight
                    row.lines.forEach { line ->
                        if (line.result) BattleSurfaceRenderer.capsule(context, boxX + 9, textY, 2, lineHeight - 3, accent, .45f)
                        draw(context, line.text, boxX + if (line.result) 15 else 9, textY, line.color, scale)
                        textY += lineHeight
                    }
                } else {
                    row.lines.forEachIndexed { index, line ->
                        val textW = mc.textRenderer.getWidth(line.text) * scale
                        draw(context, line.text, (x + (width - textW) / 2).roundToInt(), rowY + index * lineHeight, line.color, scale)
                    }
                }
            }
            rowY += row.height
        }
        if (entries.isEmpty()) drawCentered(context, tr("empty"), x + width / 2, top + viewportHeight / 2, BattleUiTheme.MUTED, scale)
        context.draw() // Text batches must flush before lifting the viewport scissor.
        context.disableScissor()
        if (maxScroll > 0) {
            val thumb = (viewportHeight.toFloat() * viewportHeight / totalHeight).toInt().coerceAtLeast(10)
            val thumbY = top + (viewportHeight - thumb) * scroll / maxScroll
            BattleSurfaceRenderer.capsule(context, x + width - 7, top, 3, bottom - top, BattleUiTheme.TRACK)
            BattleSurfaceRenderer.capsule(context, x + width - 7, thumbY, 3, thumb, BattleUiTheme.CYAN)
        }
        val close = Text.translatable("cobblemon_battle_ui.transcript.close", CobblemonExtendedBattleUIClient.toggleLogKey.boundKeyLocalizedText).string
        val closeWidth = ceil(mc.textRenderer.getWidth(close) * scale).toInt() + 12
        BattleSurfaceRenderer.capsule(context, x + width - 14 - closeWidth, bottom + 7, closeWidth + 6, lineHeight + 3, CHIP)
        closeLeft = x + width - 14 - closeWidth
        closeRight = x + width - 8
        closeTop = bottom + 9
        closeBottom = y + height - 3
        draw(context, close, closeLeft + 6, closeTop + 2, BattleUiTheme.MUTED, scale)
        context.draw()
        context.matrices.pop()
    }

    private fun layout(entries: List<BattleLog.LogEntry>, bubbleWidth: Int, innerWidth: Int, scale: Float, lineHeight: Int): List<Row> {
        val mc = MinecraftClient.getInstance()
        val result = mutableListOf<Row>()
        var lastTurn = -1
        val groups = TranscriptPolicy.group(entries.map { TranscriptPolicy.Event(it.turn, it.translationKey, it.speaker, it) })
        for (group in groups) {
            if (lastTurn != group.turn()) {
                result.add(Row(group.turn(), null, emptyList(), lineHeight + 10, true))
                lastTurn = group.turn()
            }
            if (group.kind() == TranscriptPolicy.Kind.TURN) continue
            val speaker = group.speaker()
            val lines = group.values().flatMapIndexed { index, entry ->
                val resultLine = speaker != null && index > 0
                val available = if (speaker == null) innerWidth - 16 else bubbleWidth - if (resultLine) 24 else 18
                val color = when {
                    entry.translationKey?.contains("crit") == true -> BattleUiTheme.FOCUS
                    index == 0 && speaker != null -> BattleUiTheme.TEXT
                    else -> BattleUiTheme.MUTED
                }
                mc.textRenderer.wrapLines(entry.message, (available / scale).toInt().coerceAtLeast(1)).map { Line(it, color, resultLine) }
            }
            val height = if (speaker == null) lines.size * lineHeight + 10 else 26 + lineHeight * (lines.size + 1)
            result.add(Row(group.turn(), speaker, lines, height))
        }
        return result
    }

    private fun tr(key: String) = Text.translatable("cobblemon_battle_ui.transcript.$key").string

    /** A side label on a quiet pill. */
    private fun drawTag(context: DrawContext, label: String, x: Int, y: Int, color: Int, scale: Float, lineHeight: Int) {
        val width = ceil(MinecraftClient.getInstance().textRenderer.getWidth(label) * scale).toInt() + 10
        BattleSurfaceRenderer.capsule(context, x, y - 1, width, lineHeight + 1, CHIP)
        draw(context, label, x + 5, y + 1, color, scale)
    }

    private const val CHIP = 0xFF1B2C42.toInt()
    private fun drawCentered(context: DrawContext, text: String, center: Int, y: Int, color: Int, scale: Float) {
        val width = MinecraftClient.getInstance().textRenderer.getWidth(text) * scale
        draw(context, text, (center - width / 2).roundToInt(), y, color, scale)
    }
    private fun draw(context: DrawContext, text: String, x: Int, y: Int, color: Int, scale: Float) =
        draw(context, Text.literal(text).asOrderedText(), x, y, color, scale)
    private fun draw(context: DrawContext, text: OrderedText, x: Int, y: Int, color: Int, scale: Float) {
        context.matrices.push()
        context.matrices.translate(x.toFloat(), y.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)
        context.drawText(MinecraftClient.getInstance().textRenderer, text, 0, 0, color, false)
        context.matrices.pop()
    }
}
