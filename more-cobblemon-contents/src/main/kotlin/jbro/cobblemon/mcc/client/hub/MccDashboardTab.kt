package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.client.MccBattleHubClientState
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.UiScrollState
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import java.text.NumberFormat

/**
 * First hub tab: the viewer as a trainer, their totals, and a card per content as the server's dashboard
 * sections built them. Each card's icon and order follow that content's hub tab.
 */
class MccDashboardTab : MccHubTabContent {
    private var scrollOffset = 0
    private var records: RecordsList? = null

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val layout = MccDashboardLayout.calculate(bounds)
        val presentation = MccDashboardPresentation.from(MccBattleHubClientState.dashboard) { contentId ->
            MccHubTabs.get(contentId)?.order ?: Int.MAX_VALUE
        }
        MccHubKit.card(host, layout.trainer, dashboardText("trainer"), MccHubKit.CardTone.FEATURE)
        host.add(TrainerName(layout))
        val client = Minecraft.getInstance()
        host.add(CobblemonUiRenderSlot.create(layout.model.x, layout.model.y, layout.model.width, layout.model.height,
            UiRenderSlotSpec(Component.literal(viewerName())),
            CobblemonUiRenderContent.PlayerProfile(client.player?.gameProfile ?: client.gameProfile, UiModelFraming.FULL_BODY)))
        MccHubKit.card(host, layout.records, dashboardText("records"))
        records = host.add(RecordsList(layout, presentation, scrollOffset) { scrollOffset = it })
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        records?.scroll(mouseX, mouseY, scrollY) == true

    private class TrainerName(private val layout: MccDashboardLayout) : AbstractWidget(
        layout.model.x, layout.nameLine, layout.model.width, 10, dashboardText("trainer"),
    ) {
        init {
            active = false
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
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
    ) : AbstractWidget(layout.summary.x, layout.summary.y, layout.summary.width, layout.rows.bottom - layout.summary.y,
        dashboardText("records")) {
        private val scroll = UiScrollState(
            layout.rows.height,
            presentation.contentHeight,
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
            drawSummary(graphics, theme)
            if (presentation.cards.isEmpty()) {
                drawEmpty(graphics, theme)
                return
            }
            val rows = layout.rows
            val scrollbar = if (scroll.maxOffset > 0) 4 else 0
            graphics.enableScissor(rows.x, rows.y, rows.right, rows.bottom)
            try {
                presentation.cards.forEach { placed ->
                    val top = rows.y + placed.top - scroll.offset
                    if (top + placed.height < rows.y || top > rows.bottom) return@forEach
                    drawCard(graphics, theme, placed.card, UiRect(rows.x, top, rows.width - scrollbar, placed.height), partialTick)
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

        /** One content's card: its tab icon and title, its stats in a row, its lines, and its note. */
        private fun drawCard(graphics: GuiGraphics, theme: UiThemeSnapshot, card: MccDashboardCard, bounds: UiRect, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val text = panelText(theme)
            val dim = theme.colors.textDim
            graphics.fill(bounds.x, bounds.y, bounds.right, bounds.bottom, theme.colors.panelAlt)
            graphics.fill(bounds.x, bounds.y, bounds.x + 2, bounds.bottom, theme.colors.accentPrimary)
            var y = bounds.y
            val icon = MccHubTabs.get(card.contentId)?.icon
            var titleX = bounds.x + 6
            if (icon != null) {
                CobblemonUiRenderSlot.drawContent(graphics, UiRect(bounds.x + 5, y + 1, 12, 12), icon, partialTick)
                titleX = bounds.x + 20
            }
            drawFitted(graphics, card.title, titleX, y + 3, bounds.right - titleX - 4, text)
            graphics.fill(bounds.x + 2, y + MccDashboardPresentation.HEADER - 2, bounds.right, y + MccDashboardPresentation.HEADER - 1,
                theme.colors.border)
            y += MccDashboardPresentation.HEADER
            if (card.stats.isNotEmpty()) {
                val cellWidth = (bounds.width - 6) / card.stats.size
                card.stats.forEachIndexed { index, stat ->
                    val left = bounds.x + 6 + index * cellWidth
                    if (index > 0) graphics.fill(left - 3, y + 2, left - 2, y + MccDashboardPresentation.STATS - 4, theme.colors.border)
                    drawFitted(graphics, stat.label, left, y + 1, cellWidth - 6, dim)
                    drawFitted(graphics, stat.value, left, y + 11, cellWidth - 6, text)
                }
                y += MccDashboardPresentation.STATS
            }
            card.rows.forEach { row ->
                val valueWidth = font.width(row.value)
                drawFitted(graphics, row.title, bounds.x + 6, y + 1, bounds.width - valueWidth - 16, text)
                graphics.drawString(font, row.value, bounds.right - valueWidth - 5, y + 1, text, false)
                y += MccDashboardPresentation.ROW
                row.detail?.let { detail ->
                    drawFitted(graphics, detail, bounds.x + 6, y, bounds.width - 12, dim)
                    y += MccDashboardPresentation.ROW_DETAIL - MccDashboardPresentation.ROW
                }
            }
            card.note?.let { drawFitted(graphics, it, bounds.x + 6, y + 1, bounds.width - 12, dim) }
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

        fun panelText(theme: UiThemeSnapshot): Int = MccHubKit.panelText(theme)
    }
}

internal fun dashboardText(key: String, vararg args: Any): Component =
    Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.dashboard.$key", *args)
