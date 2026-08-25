package jbro.cobblemon.morebattlecontent.client

import kotlin.math.roundToInt
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

internal data class TowerGuideSection(
    val titleKey: String,
    val bodyKey: String,
)

internal object TowerGuideContent {
    const val TITLE_KEY = "screen.cobblemon_more_battle_content.tower.guide.title"
    const val CLOSE_KEY = "screen.cobblemon_more_battle_content.tower.guide.close"
    const val BUTTON_TOOLTIP_KEY = "screen.cobblemon_more_battle_content.tower.guide.button.tooltip"

    val sections: List<TowerGuideSection> = listOf(
        section("overview"),
        section("registration"),
        section("setup"),
        section("legendary"),
        section("registered_team"),
        section("progression"),
        section("controls"),
        section("troubleshooting"),
    )

    private fun section(id: String) = TowerGuideSection(
        titleKey = "screen.cobblemon_more_battle_content.tower.guide.$id.title",
        bodyKey = "screen.cobblemon_more_battle_content.tower.guide.$id.body",
    )
}

internal class TowerGuideLayout private constructor(
    val shell: TowerPlayRect,
    val header: TowerPlayRect,
    val closeButton: TowerPlayRect,
    val body: TowerPlayRect,
    val viewport: TowerPlayRect,
    val scrollTrack: TowerPlayRect,
) {
    companion object {
        fun calculate(screenWidth: Int, screenHeight: Int): TowerGuideLayout {
            val shellWidth = (screenWidth - SCREEN_MARGIN * 2).coerceAtMost(MAX_WIDTH)
            val shellHeight = (screenHeight - SCREEN_MARGIN * 2).coerceAtMost(MAX_HEIGHT)
            require(shellWidth >= MIN_WIDTH) { "Screen is too narrow for the Battle Tower guide" }
            require(shellHeight >= MIN_HEIGHT) { "Screen is too short for the Battle Tower guide" }

            val shell = TowerPlayRect(
                (screenWidth - shellWidth) / 2,
                (screenHeight - shellHeight) / 2,
                shellWidth,
                shellHeight,
            )
            val header = TowerPlayRect(shell.left + INSET, shell.top + INSET, shell.width - INSET * 2, HEADER_HEIGHT)
            val closeButton = TowerPlayRect(header.right - HEADER_HEIGHT, header.top, HEADER_HEIGHT, HEADER_HEIGHT)
            val body = TowerPlayRect(
                header.left,
                header.bottom + PANEL_GAP,
                header.width,
                shell.bottom - INSET - header.bottom - PANEL_GAP,
            )
            val scrollTrack = TowerPlayRect(
                body.right - BODY_INSET - SCROLL_WIDTH,
                body.top + BODY_INSET,
                SCROLL_WIDTH,
                body.height - BODY_INSET * 2,
            )
            val viewport = TowerPlayRect(
                body.left + BODY_INSET,
                body.top + BODY_INSET,
                scrollTrack.left - SCROLL_GAP - body.left - BODY_INSET,
                body.height - BODY_INSET * 2,
            )
            return TowerGuideLayout(shell, header, closeButton, body, viewport, scrollTrack)
        }
    }
}

internal class TowerGuideScreen(
    internal val towerPlayScreen: TowerPlayScreen,
) : MbcScreen(Component.translatable(TowerGuideContent.TITLE_KEY)) {
    private var scrollOffset = 0
    private var draggingScroll = false
    private var dragGrabOffset = 0

    override fun init() {
        val layout = TowerGuideLayout.calculate(width, height)
        val closeButton = MbcStyledButton(
            layout.closeButton,
            Component.literal("×"),
            MbcButtonTone.DANGER,
        ) { onClose() }
        closeButton.setTooltip(
            Tooltip.create(Component.translatable(TowerGuideContent.CLOSE_KEY)),
        )
        addRenderableWidget(closeButton)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val layout = TowerGuideLayout.calculate(width, height)
        val rows = wrappedRows(layout)
        val metrics = scrollMetrics(layout, rows)
        scrollOffset = scrollOffset.coerceIn(0, metrics.maxOffset)

        MbcGuiSurface.drawBackdrop(graphics, width, height)
        MbcGuiSurface.drawShell(graphics, layout.shell)
        MbcGuiSurface.drawPanel(graphics, layout.header, MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.body, MbcGuiPalette.ACCENT_PRIMARY)
        graphics.drawString(
            font,
            title,
            layout.header.left + 7,
            layout.header.top + (layout.header.height - font.lineHeight) / 2,
            MbcGuiPalette.ACCENT_PRIMARY,
            false,
        )
        drawRows(graphics, layout, rows)
        drawScrollBar(graphics, layout.scrollTrack, metrics)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val layout = TowerGuideLayout.calculate(width, height)
        if (!layout.viewport.contains(mouseX, mouseY) && !layout.scrollTrack.contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        val metrics = scrollMetrics(layout, wrappedRows(layout))
        val delta = if (scrollY != 0.0) scrollY else scrollX
        scrollOffset = metrics.afterWheel(scrollOffset, delta)
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val layout = TowerGuideLayout.calculate(width, height)
            val metrics = scrollMetrics(layout, wrappedRows(layout))
            if (layout.scrollTrack.contains(mouseX, mouseY) && metrics.maxOffset > 0) {
                val thumbTop = metrics.thumbTop(layout.scrollTrack.top, scrollOffset)
                val insideThumb = mouseY >= thumbTop && mouseY < thumbTop + metrics.thumbHeight
                draggingScroll = true
                dragGrabOffset = if (insideThumb) (mouseY - thumbTop).roundToInt() else metrics.thumbHeight / 2
                scrollOffset = metrics.offsetForThumbTop(
                    layout.scrollTrack.top,
                    mouseY.roundToInt() - dragGrabOffset,
                )
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        if (button == 0 && draggingScroll) {
            val layout = TowerGuideLayout.calculate(width, height)
            val metrics = scrollMetrics(layout, wrappedRows(layout))
            scrollOffset = metrics.offsetForThumbTop(
                layout.scrollTrack.top,
                mouseY.roundToInt() - dragGrabOffset,
            )
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && draggingScroll) {
            draggingScroll = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun onClose() {
        minecraft?.setScreen(towerPlayScreen)
    }

    override fun isPauseScreen(): Boolean = false

    private fun wrappedRows(layout: TowerGuideLayout): List<GuideRow> = buildList {
        TowerGuideContent.sections.forEachIndexed { index, section ->
            if (index > 0) add(GuideRow(null, MbcGuiPalette.TEXT_PRIMARY, SECTION_GAP))
            font.split(Component.translatable(section.titleKey), layout.viewport.width - TEXT_INSET * 2).forEach { line ->
                add(GuideRow(line, MbcGuiPalette.ACCENT_SECONDARY, TITLE_LINE_HEIGHT))
            }
            add(GuideRow(null, MbcGuiPalette.TEXT_PRIMARY, 2))
            font.split(Component.translatable(section.bodyKey), layout.viewport.width - TEXT_INSET * 2).forEach { line ->
                add(GuideRow(line, MbcGuiPalette.TEXT_PRIMARY, BODY_LINE_HEIGHT))
            }
        }
    }

    private fun scrollMetrics(layout: TowerGuideLayout, rows: List<GuideRow>): MbcVerticalScrollMetrics =
        MbcVerticalScrollMetrics.calculate(
            viewportHeight = layout.viewport.height,
            contentHeight = maxOf(1, rows.sumOf(GuideRow::height) + TEXT_INSET * 2),
            trackHeight = layout.scrollTrack.height,
        )

    private fun drawRows(graphics: GuiGraphics, layout: TowerGuideLayout, rows: List<GuideRow>) {
        graphics.enableScissor(layout.viewport.left, layout.viewport.top, layout.viewport.right, layout.viewport.bottom)
        var rowTop = layout.viewport.top + TEXT_INSET - scrollOffset
        rows.forEach { row ->
            row.text?.let { text ->
                if (rowTop + row.height > layout.viewport.top && rowTop < layout.viewport.bottom) {
                    graphics.drawString(font, text, layout.viewport.left + TEXT_INSET, rowTop, row.color, false)
                }
            }
            rowTop += row.height
        }
        graphics.disableScissor()
    }

    private fun drawScrollBar(
        graphics: GuiGraphics,
        track: TowerPlayRect,
        metrics: MbcVerticalScrollMetrics,
    ) {
        graphics.fill(track.left, track.top, track.right, track.bottom, MbcGuiPalette.BORDER)
        graphics.fill(track.left + 1, track.top + 1, track.right - 1, track.bottom - 1, MbcGuiPalette.BUTTON_DISABLED)
        val thumbTop = metrics.thumbTop(track.top, scrollOffset)
        graphics.fill(
            track.left + 1,
            thumbTop,
            track.right - 1,
            thumbTop + metrics.thumbHeight,
            if (metrics.maxOffset == 0) MbcGuiPalette.BORDER else MbcGuiPalette.ACCENT_PRIMARY,
        )
    }

    private data class GuideRow(
        val text: FormattedCharSequence?,
        val color: Int,
        val height: Int,
    )
}

private fun TowerPlayRect.contains(x: Double, y: Double): Boolean =
    x >= left && x < right && y >= top && y < bottom

private const val SCREEN_MARGIN = 8
private const val MAX_WIDTH = 520
private const val MAX_HEIGHT = 340
private const val MIN_WIDTH = 284
private const val MIN_HEIGHT = 200
private const val INSET = 6
private const val HEADER_HEIGHT = 22
private const val PANEL_GAP = 4
private const val BODY_INSET = 6
private const val SCROLL_WIDTH = 8
private const val SCROLL_GAP = 5
private const val TEXT_INSET = 3
private const val SECTION_GAP = 7
private const val TITLE_LINE_HEIGHT = 12
private const val BODY_LINE_HEIGHT = 11
