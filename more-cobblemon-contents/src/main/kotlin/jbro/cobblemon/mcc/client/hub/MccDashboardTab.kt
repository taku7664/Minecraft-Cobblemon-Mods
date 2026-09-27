package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.MccBattleHubClientState
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiPanelSpec
import jbro.cobblemon.uikit.UiPanelTone
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.UiScrollState
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.client.CobblemonUiPanel
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import java.text.NumberFormat

/** First hub tab: the viewer as a trainer, their BP and their records across every content. */
class MccDashboardTab : MccHubTabContent {
    private var scrollOffset = 0
    private var records: RecordsList? = null

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val layout = MccDashboardLayout.calculate(bounds)
        val presentation = MccDashboardPresentation.from(MccBattleHubClientState.dashboard.orEmpty()) { contentId ->
            MccHubTabs.get(contentId)?.order ?: Int.MAX_VALUE
        }
        host.add(CobblemonUiPanel.create(layout.trainer.x, layout.trainer.y, layout.trainer.width, layout.trainer.height,
            UiPanelSpec(tone = UiPanelTone.RAISED)))
        host.add(TrainerCard(layout))
        val client = Minecraft.getInstance()
        host.add(CobblemonUiRenderSlot.create(layout.model.x, layout.model.y, layout.model.width, layout.model.height,
            UiRenderSlotSpec(Component.literal(viewerName())),
            CobblemonUiRenderContent.PlayerProfile(client.player?.gameProfile ?: client.gameProfile, UiModelFraming.FULL_BODY)))
        host.add(CobblemonUiPanel.create(layout.records.x, layout.records.y, layout.records.width, layout.records.height,
            UiPanelSpec(tone = UiPanelTone.RAISED)))
        records = host.add(RecordsList(layout, presentation, scrollOffset) { scrollOffset = it })
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        records?.scroll(mouseX, mouseY, scrollY) == true

    private class TrainerCard(private val layout: MccDashboardLayout) : AbstractWidget(
        layout.trainer.x, layout.trainer.y, layout.trainer.width, layout.trainer.height, dashboardText("trainer"),
    ) {
        init {
            active = false
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            drawStrip(graphics, theme, layout.trainerStrip, dashboardText("trainer"), theme.colors.accentCaution)
            val font = Minecraft.getInstance().font
            val name = font.plainSubstrByWidth(viewerName(), layout.trainer.width - 16)
            graphics.drawString(font, name, layout.trainer.x + (layout.trainer.width - font.width(name)) / 2,
                layout.nameLine, panelText(theme), false)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, Component.literal(viewerName()))
        }
    }

    private class RecordsList(
        private val layout: MccDashboardLayout,
        private val presentation: MccDashboardPresentation,
        initialOffset: Int,
        private val offsetChanged: (Int) -> Unit,
    ) : AbstractWidget(layout.records.x, layout.records.y, layout.records.width, layout.records.height, dashboardText("records")) {
        private val scroll = UiScrollState(
            layout.rows.height,
            presentation.rows.size * MccDashboardLayout.ROW_HEIGHT,
            MccDashboardLayout.ROW_HEIGHT,
        ).also { it.jumpTo(initialOffset) }

        init {
            active = false
        }

        fun scroll(mouseX: Double, mouseY: Double, delta: Double): Boolean {
            val rows = layout.rows
            if (mouseX < rows.x || mouseX >= rows.right || mouseY < rows.y || mouseY >= rows.bottom) return false
            val moved = scroll.scroll(delta)
            if (moved) offsetChanged(scroll.offset)
            return moved
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            drawStrip(graphics, theme, layout.recordsStrip, dashboardText("records"), theme.colors.borderBright)
            drawSummary(graphics, theme)
            if (presentation.rows.isEmpty()) {
                drawEmpty(graphics, theme)
                return
            }
            val rows = layout.rows
            graphics.enableScissor(rows.x, rows.y, rows.right, rows.bottom)
            try {
                val scrollbar = if (scroll.maxOffset > 0) 4 else 0
                presentation.rows.forEachIndexed { index, row ->
                    val top = rows.y + index * MccDashboardLayout.ROW_HEIGHT - scroll.offset
                    if (top + MccDashboardLayout.ROW_HEIGHT < rows.y || top > rows.bottom) return@forEachIndexed
                    drawRow(graphics, theme, row, index, UiRect(rows.x, top, rows.width - scrollbar, MccDashboardLayout.ROW_HEIGHT - 2))
                }
            } finally {
                graphics.disableScissor()
            }
            if (scroll.maxOffset > 0) {
                val track = UiRect(rows.right - 3, rows.y, 3, rows.height)
                graphics.fill(track.x, track.y, track.right, track.bottom, theme.colors.border)
                val thumb = scroll.thumb(track.y, track.height)
                graphics.fill(track.x, thumb.start, track.right, thumb.endExclusive, theme.colors.accentPrimary)
            }
        }

        private fun drawSummary(graphics: GuiGraphics, theme: UiThemeSnapshot) {
            val summary = layout.summary
            val cells = listOf(
                dashboardText("summary.battles") to number(presentation.battles),
                dashboardText("summary.wins") to number(presentation.wins),
                dashboardText("summary.win_rate") to (presentation.winRatePercent?.let { Component.literal("$it%") }
                    ?: Component.literal("-")),
            )
            val cellWidth = summary.width / cells.size
            cells.forEachIndexed { index, (label, value) ->
                val left = summary.x + index * cellWidth
                if (index > 0) graphics.fill(left - 1, summary.y + 2, left, summary.bottom - 2, theme.colors.border)
                drawFitted(graphics, label, left + 4, summary.y + 1, cellWidth - 8, theme.colors.textDim)
                drawFitted(graphics, value, left + 4, summary.y + 12, cellWidth - 8, panelText(theme))
            }
            graphics.fill(summary.x, summary.bottom + 1, summary.right, summary.bottom + 2, theme.colors.border)
        }

        private fun drawEmpty(graphics: GuiGraphics, theme: UiThemeSnapshot) {
            val font = Minecraft.getInstance().font
            val rows = layout.rows
            val lines = font.split(dashboardText("empty"), (rows.width - 16).coerceAtLeast(1))
            val top = rows.y + (rows.height - lines.size * (font.lineHeight + 1)) / 2
            lines.forEachIndexed { index, line ->
                graphics.drawString(font, line, rows.x + (rows.width - font.width(line)) / 2,
                    top + index * (font.lineHeight + 1), theme.colors.textDim, false)
            }
        }

        private fun drawRow(graphics: GuiGraphics, theme: UiThemeSnapshot, row: MccDashboardPresentation.Row, index: Int, bounds: UiRect) {
            val font = Minecraft.getInstance().font
            graphics.fill(bounds.x, bounds.y, bounds.right, bounds.bottom, if (index % 2 == 0) theme.colors.panel else theme.colors.panelAlt)
            graphics.fill(bounds.x, bounds.y, bounds.x + 2, bounds.bottom, theme.colors.accentPrimary)
            val text = panelText(theme)
            val record = dashboardText("row.record", row.wins, row.losses)
            val recordWidth = font.width(record)
            val title = Component.empty().append(translatedOr(row.contentNameKey, row.contentId))
                .append(Component.literal(" · ")).append(translatedOr(row.formatNameKey, row.formatId))
            drawFitted(graphics, title, bounds.x + 6, bounds.y + 2, bounds.width - recordWidth - 16, text)
            graphics.drawString(font, record, bounds.right - recordWidth - 5, bounds.y + 2, text, false)
            val details = Component.empty().append(dashboardText("row.streak", row.currentStreak, row.bestStreak))
            row.metrics.forEach { (key, value) ->
                details.append(Component.literal(" · ")).append(
                    if (I18n.exists(key)) Component.translatable(key, value) else Component.literal("${key.substringAfterLast('.')} $value"),
                )
            }
            drawFitted(graphics, details, bounds.x + 6, bounds.y + 11, bounds.width - 12, theme.colors.textDim)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, dashboardText("records"))
        }
    }

    private companion object {
        fun viewerName(): String {
            val client = Minecraft.getInstance()
            return client.player?.gameProfile?.name ?: client.user.name
        }

        fun number(value: Long): Component = Component.literal(NumberFormat.getIntegerInstance().format(value))

        fun translatedOr(key: String, fallback: String): Component =
            if (I18n.exists(key)) Component.translatable(key) else Component.literal(fallback)

        fun panelText(theme: UiThemeSnapshot): Int = theme.surfaces.panelAltText ?: theme.colors.textPrimary

        fun drawStrip(graphics: GuiGraphics, theme: UiThemeSnapshot, strip: UiRect, title: Component, color: Int) {
            graphics.fill(strip.x, strip.y, strip.right, strip.bottom, color)
            drawFitted(graphics, title, strip.x + 5, strip.y + 4, strip.width - 10, theme.colors.shell)
        }
    }
}

internal fun dashboardText(key: String, vararg args: Any): Component =
    Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.dashboard.$key", *args)
