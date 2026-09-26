package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiCorner
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceOverrides
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

class ComponentGalleryScreen : Screen(text("title")) {
    private data class ScrollingWidget(val widget: AbstractWidget, val contentY: Int)

    private val scrollingWidgets = mutableListOf<ScrollingWidget>()
    private val sectionY = linkedMapOf<String, Int>()
    private var shellLeft = 0
    private var shellTop = 0
    private var shellWidth = 0
    private var viewportTop = 0
    private var viewportBottom = 0
    private var contentHeight = 0
    private var scrollOffset = 0

    override fun init() {
        clearWidgets()
        scrollingWidgets.clear()
        sectionY.clear()

        shellWidth = min(520, width - 20).coerceAtLeast(260)
        shellLeft = (width - shellWidth) / 2
        shellTop = 12
        viewportTop = shellTop + 40
        viewportBottom = height - 34
        val contentLeft = shellLeft + 12
        val contentRight = shellLeft + shellWidth - 12
        val availableWidth = contentRight - contentLeft
        var cursorY = 8

        sectionY["buttons"] = cursorY
        cursorY += 15
        cursorY = addFlow(
            contentLeft,
            contentRight,
            cursorY,
            listOf(
                UiButtonSpec(text("primary"), variant = UiButtonVariant.PRIMARY, size = UiControlSize.SMALL),
                UiButtonSpec(text("secondary"), variant = UiButtonVariant.SECONDARY),
                UiButtonSpec(
                    text("icon"),
                    icon = UiIcon("cobblemon_ui_kit", "textures/gui/icons/info.png"),
                    variant = UiButtonVariant.ICON,
                    size = UiControlSize.SMALL
                ),
                UiButtonSpec(text("ghost"), variant = UiButtonVariant.GHOST),
                UiButtonSpec(
                    text("custom_surface"),
                    variant = UiButtonVariant.SECONDARY,
                    size = UiControlSize.SMALL,
                    surfaceOverrides = UiSurfaceOverrides(
                        shape = UiShape.Chamfer(
                            5,
                            setOf(UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT)
                        ),
                        fill = UiFill.VerticalGradient(
                            0xFF7956C8.toInt(),
                            0xFF223D63.toInt()
                        ),
                        border = UiBorder.None,
                        backgroundOpacity = 0.78f
                    )
                ),
                UiButtonSpec(
                    text("danger"),
                    supportingText = text("supporting"),
                    variant = UiButtonVariant.DANGER,
                    size = UiControlSize.LARGE,
                    width = UiWidthPolicy.Fill
                )
            ),
            availableWidth
        )

        cursorY += 10
        sectionY["states"] = cursorY
        cursorY += 15
        val stateSpecs = UiWidgetState.entries.map { state ->
            UiButtonSpec(
                title = text(state.name.lowercase()),
                variant = UiButtonVariant.SECONDARY,
                size = UiControlSize.SMALL,
                selected = state == UiWidgetState.SELECTED
            ) to state
        }
        cursorY = addStateFlow(contentLeft, contentRight, cursorY, stateSpecs, availableWidth)

        cursorY += 10
        sectionY["list"] = cursorY
        contentHeight = cursorY + 96

        val close = CobblemonUiButton.create(
            shellLeft + shellWidth - 78,
            height - 28,
            68,
            UiButtonSpec(text("close"), variant = UiButtonVariant.GHOST, size = UiControlSize.SMALL),
            press = ::onClose
        )
        addRenderableWidget(close)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        updateWidgetPositions()
    }

    private fun addFlow(
        left: Int,
        right: Int,
        startY: Int,
        specs: List<UiButtonSpec>,
        availableWidth: Int
    ): Int {
        var x = left
        var y = startY
        var rowHeight = 0
        specs.forEach { spec ->
            var widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec)
            if (x != left && x + widget.width > right) {
                x = left
                y += rowHeight + 5
                rowHeight = 0
                widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec)
            }
            addWidget(widget)
            scrollingWidgets += ScrollingWidget(widget, y)
            x += widget.width + 5
            rowHeight = max(rowHeight, widget.height)
        }
        return y + rowHeight
    }

    private fun addStateFlow(
        left: Int,
        right: Int,
        startY: Int,
        specs: List<Pair<UiButtonSpec, UiWidgetState>>,
        availableWidth: Int
    ): Int {
        var x = left
        var y = startY
        var rowHeight = 0
        specs.forEach { (spec, state) ->
            var widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec, state)
            if (x != left && x + widget.width > right) {
                x = left
                y += rowHeight + 5
                rowHeight = 0
                widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec, state)
            }
            addWidget(widget)
            scrollingWidgets += ScrollingWidget(widget, y)
            x += widget.width + 5
            rowHeight = max(rowHeight, widget.height)
        }
        return y + rowHeight
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ) = Unit

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        graphics.fill(0, 0, width, height, theme.colors.backdrop)
        UiSurfaceRenderer.draw(
            graphics,
            shellLeft,
            shellTop,
            shellWidth,
            height - shellTop - 8,
            theme.surfaces.shell
        )
        graphics.drawString(font, title, shellLeft + 12, shellTop + 10, theme.colors.textPrimary, false)
        graphics.drawString(font, text("subtitle"), shellLeft + 12, shellTop + 24, theme.colors.textSecondary, false)

        graphics.enableScissor(shellLeft + 1, viewportTop, shellLeft + shellWidth - 1, viewportBottom)
        try {
            sectionY.forEach { (key, contentY) ->
                graphics.drawString(
                    font,
                    text(key),
                    shellLeft + 12,
                    viewportTop + contentY - scrollOffset,
                    theme.colors.accentPrimary,
                    false
                )
            }
            drawListAndProgress(graphics)
            scrollingWidgets.forEach { entry ->
                if (entry.widget.visible) entry.widget.render(graphics, mouseX, mouseY, partialTick)
            }
        } finally {
            graphics.disableScissor()
        }

        super.render(graphics, mouseX, mouseY, partialTick)

        if (maxScroll() > 0) {
            val trackTop = viewportTop + 3
            val trackHeight = viewportBottom - viewportTop - 6
            val thumbHeight = max(18, trackHeight * (viewportBottom - viewportTop) / contentHeight)
            val thumbTravel = trackHeight - thumbHeight
            val thumbTop = trackTop + if (maxScroll() == 0) 0 else thumbTravel * scrollOffset / maxScroll()
            graphics.fill(shellLeft + shellWidth - 5, trackTop, shellLeft + shellWidth - 3, trackTop + trackHeight, theme.colors.panelAlt)
            graphics.fill(shellLeft + shellWidth - 5, thumbTop, shellLeft + shellWidth - 3, thumbTop + thumbHeight, theme.colors.accentPrimary)
        }
    }

    private fun drawListAndProgress(graphics: GuiGraphics) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val top = viewportTop + sectionY.getValue("list") + 15 - scrollOffset
        val left = shellLeft + 12
        val right = shellLeft + shellWidth - 12
        UiSurfaceRenderer.draw(graphics, left, top, right - left, 64, theme.surfaces.panel)
        repeat(3) { index ->
            val rowTop = top + 6 + index * 17
            val rowStyle = theme.surfaces.panelAlt.copy(
                fill = UiFill.Solid(if (index == 1) theme.colors.panelAlt else theme.colors.shell)
            )
            UiSurfaceRenderer.draw(graphics, left + 6, rowTop, right - left - 12, 13, rowStyle)
            graphics.drawString(font, "${index + 1}. ${text("rank").string}", left + 10, rowTop + 3, theme.colors.textSecondary, false)
        }
        val barTop = top + 70
        UiSurfaceRenderer.draw(
            graphics,
            left,
            barTop,
            right - left,
            8,
            theme.surfaces.panelAlt.copy(
                shape = UiShape.Chamfer(2),
                border = UiBorder.Solid(theme.colors.border)
            )
        )
        UiSurfaceRenderer.draw(
            graphics,
            left + 1,
            barTop + 1,
            (right - left - 2) * 5 / 8,
            6,
            UiSurfaceStyle(
                shape = UiShape.Chamfer(1),
                fill = UiFill.VerticalGradient(theme.colors.accentGood, 0xFF28794E.toInt()),
                border = UiBorder.None
            )
        )
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseX.toInt() in shellLeft..(shellLeft + shellWidth) && mouseY.toInt() in viewportTop..viewportBottom) {
            val delta = if (scrollY != 0.0) scrollY else scrollX
            val next = (scrollOffset - (delta * 18).toInt()).coerceIn(0, maxScroll())
            if (next != scrollOffset) {
                scrollOffset = next
                updateWidgetPositions()
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun updateWidgetPositions() {
        scrollingWidgets.forEach { entry ->
            entry.widget.y = viewportTop + entry.contentY - scrollOffset
            entry.widget.visible = entry.widget.y + entry.widget.height > viewportTop && entry.widget.y < viewportBottom
        }
    }

    private fun maxScroll(): Int = max(0, contentHeight - (viewportBottom - viewportTop))

    companion object {
        private fun text(suffix: String): Component =
            Component.translatable("screen.cobblemon_ui_kit.gallery.$suffix")
    }
}
