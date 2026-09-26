package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.CobblemonUiThemePresets
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiBadgeSpec
import jbro.cobblemon.uikit.UiBadgeTone
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiCardSpec
import jbro.cobblemon.uikit.UiCheckboxSpec
import jbro.cobblemon.uikit.UiChoiceOption
import jbro.cobblemon.uikit.UiComboBoxSpec
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiCorner
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiIconButtonShape
import jbro.cobblemon.uikit.UiFlowLayout
import jbro.cobblemon.uikit.UiInsets
import jbro.cobblemon.uikit.UiLayoutItem
import jbro.cobblemon.uikit.UiListItemSpec
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.UiProgressSpec
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiStatRowSpec
import jbro.cobblemon.uikit.UiSurfaceOverrides
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiTabSpec
import jbro.cobblemon.uikit.UiThemePreset
import jbro.cobblemon.uikit.UiToastSpec
import jbro.cobblemon.uikit.UiTooltipSpec
import jbro.cobblemon.uikit.UiToggleSpec
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

class ComponentGalleryScreen(
    private val preset: UiThemePreset = CobblemonUiThemePresets.currentPreset()
) : Screen(text("title")) {
    private data class ScrollingWidget(val widget: AbstractWidget, val contentY: Int)

    private val scrollingWidgets = mutableListOf<ScrollingWidget>()
    private val sectionY = linkedMapOf<String, Int>()
    private var shellLeft = 0
    private var shellTop = 0
    private var shellWidth = 0
    private var viewportTop = 0
    private var viewportBottom = 0
    private var contentHeight = 0
    private lateinit var scrollViewport: CobblemonUiScrollViewport
    private val toastLayer = CobblemonUiToastLayer()
    private var tooltipTarget: AbstractWidget? = null

    override fun init() {
        CobblemonUiThemePresets.install(preset)
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

        sectionY["themes"] = cursorY
        cursorY += 15
        cursorY = addActionFlow(
            contentLeft,
            contentRight,
            cursorY,
            UiThemePreset.entries.map { candidate ->
                UiButtonSpec(
                    title = text("theme.${candidate.id}"),
                    variant = UiButtonVariant.SECONDARY,
                    size = UiControlSize.SMALL,
                    selected = candidate == preset
                ) to {
                    CobblemonUiThemePresets.install(candidate)
                    minecraft?.setScreen(ComponentGalleryScreen(candidate))
                }
            },
            availableWidth
        )

        cursorY += 10
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
                    icon = UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                    variant = UiButtonVariant.ICON,
                    size = UiControlSize.SMALL
                ),
                UiButtonSpec(text("ghost"), variant = UiButtonVariant.GHOST),
                UiButtonSpec(
                    text("custom_surface"),
                    variant = UiButtonVariant.DANGER,
                    size = UiControlSize.SMALL,
                    surfaceOverrides = customSurfaceOverrides()
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
        sectionY["shapes"] = cursorY
        cursorY += 15
        cursorY = addFlow(
            contentLeft,
            contentRight,
            cursorY,
            listOf(
                UiButtonSpec.iconOnly(
                    text("icon_square"),
                    UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                    UiIconButtonShape.SQUARE
                ),
                UiButtonSpec.iconOnly(
                    text("icon_circle"),
                    UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                    UiIconButtonShape.CIRCLE
                ),
                UiButtonSpec.iconOnly(
                    text("icon_diamond"),
                    UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                    UiIconButtonShape.DIAMOND
                ),
                UiButtonSpec(
                    text("rounded"),
                    size = UiControlSize.SMALL,
                    surfaceOverrides = UiSurfaceOverrides(shape = UiShape.RoundedRectangle(6))
                ),
                UiButtonSpec(
                    text("capsule"),
                    size = UiControlSize.SMALL,
                    surfaceOverrides = UiSurfaceOverrides(shape = UiShape.Capsule)
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
        sectionY["widgets"] = cursorY
        cursorY += 15

        var tabX = contentLeft
        listOf(
            UiTabSpec(text("tab_gyms"), selected = true, width = UiWidthPolicy.Fixed(74)),
            UiTabSpec(text("tab_elite"), width = UiWidthPolicy.Fixed(74)),
            UiTabSpec(text("tab_champion"), width = UiWidthPolicy.Fixed(84))
        ).forEach { spec ->
            val tab = CobblemonUiTab.create(tabX, viewportTop + cursorY, availableWidth, spec)
            addScrollingWidget(tab, cursorY)
            tabX += tab.width + 4
        }
        cursorY += 25

        var badgeX = contentLeft
        listOf(
            UiBadgeSpec(text("badge_ready"), UiBadgeTone.SUCCESS),
            UiBadgeSpec(text("badge_locked"), UiBadgeTone.WARNING),
            UiBadgeSpec(text("badge_master"), UiBadgeTone.INFO)
        ).forEach { spec ->
            val badge = CobblemonUiBadge.create(badgeX, viewportTop + cursorY, spec)
            addScrollingWidget(badge, cursorY)
            badgeX += badge.width + 5
        }
        cursorY += 22

        val toggle = CobblemonUiToggle.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiToggleSpec(text("toggle_level_cap"), value = true, width = UiWidthPolicy.Fill)
        )
        addScrollingWidget(toggle, cursorY)
        cursorY += toggle.height + 5

        val listItem = CobblemonUiListItem.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiListItemSpec(
                title = text("list_gym"),
                supportingText = text("list_gym_detail"),
                icon = UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                trailingText = text("list_gym_status"),
                selected = true
            )
        )
        addScrollingWidget(listItem, cursorY)
        cursorY += listItem.height + 6

        val progress = CobblemonUiProgressBar.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiProgressSpec(5, 8, text("progress_badges"), showValue = true)
        )
        addScrollingWidget(progress, cursorY)
        cursorY += progress.height

        cursorY += 10
        sectionY["advanced"] = cursorY
        cursorY += 15

        val checkbox = CobblemonUiCheckbox.create(
            0,
            0,
            UiCheckboxSpec(text("checkbox_remember"), checked = true)
        )
        val dialogButton = CobblemonUiButton.create(
            0,
            0,
            availableWidth,
            UiButtonSpec(text("open_dialog"), variant = UiButtonVariant.SECONDARY, size = UiControlSize.SMALL)
        ) { openDemoDialog() }
        val toastButton = CobblemonUiButton.create(
            0,
            0,
            availableWidth,
            UiButtonSpec(text("show_toast"), variant = UiButtonVariant.SECONDARY, size = UiControlSize.SMALL)
        ) {
            toastLayer.show(UiToastSpec(text("toast_saved"), UiOverlayTone.SUCCESS))
        }
        val helpButton = CobblemonUiButton.create(
            0,
            0,
            availableWidth,
            UiButtonSpec.iconOnly(
                text("tooltip_title"),
                UiIcon("cobblemon_ui_kit", "textures/gui/pixel/info.png"),
                UiIconButtonShape.CIRCLE,
                UiControlSize.SMALL
            )
        )
        tooltipTarget = helpButton

        val flowWidgets = linkedMapOf<String, AbstractWidget>(
            "checkbox" to checkbox,
            "dialog" to dialogButton,
            "toast" to toastButton,
            "help" to helpButton
        )
        val flowPlacements = UiFlowLayout(5, 5, UiInsets.None).place(
            availableWidth,
            flowWidgets.map { (key, widget) -> UiLayoutItem(key, widget.width, widget.height) }
        )
        CobblemonUiLayout.apply(contentLeft, viewportTop + cursorY, flowPlacements, flowWidgets)
        flowPlacements.forEach { placement ->
            addScrollingWidget(flowWidgets.getValue(placement.key), cursorY + placement.bounds.y)
        }
        cursorY += (flowPlacements.maxOfOrNull { it.bounds.bottom } ?: 0) + 6

        val radioGroup = CobblemonUiRadioGroup(
            listOf(
                UiChoiceOption("single", text("radio_single")),
                UiChoiceOption("double", text("radio_double")),
                UiChoiceOption("multi", text("radio_multi"), enabled = false)
            )
        )
        radioGroup.createButtons(contentLeft, viewportTop + cursorY).forEach { radio ->
            addScrollingWidget(radio, cursorY)
            cursorY += radio.height + 2
        }

        val comboOptions = listOf(
            UiChoiceOption("monster", text("combo_monster")),
            UiChoiceOption("great", text("combo_great")),
            UiChoiceOption("ultra", text("combo_ultra")),
            UiChoiceOption("master", text("combo_master"))
        )
        val combo = CobblemonUiComboBox.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiComboBoxSpec(
                text("combo_rank"),
                comboOptions,
                selectedIndex = 2,
                width = UiWidthPolicy.Fixed(150)
            )
        )
        addScrollingWidget(combo, cursorY)
        cursorY += combo.height + 6

        val card = CobblemonUiCard.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiCardSpec(text("card_title"), text("card_body"), selected = true)
        )
        addScrollingWidget(card, cursorY)
        cursorY += card.height + 6

        val stat = CobblemonUiStatRow.create(
            contentLeft,
            viewportTop + cursorY,
            availableWidth,
            UiStatRowSpec(text("stat_label"), text("stat_value"), progress = 0.72f)
        )
        addScrollingWidget(stat, cursorY)
        cursorY += stat.height

        contentHeight = cursorY + 10
        scrollViewport = CobblemonUiScrollViewport(
            shellLeft + 1,
            viewportTop,
            shellWidth - 2,
            viewportBottom - viewportTop,
            contentHeight
        )
        scrollingWidgets.forEach { scrollViewport.register(it.widget, it.contentY) }

        val close = CobblemonUiButton.create(
            shellLeft + shellWidth - 78,
            height - 28,
            68,
            UiButtonSpec(text("close"), variant = UiButtonVariant.GHOST, size = UiControlSize.SMALL),
            press = ::onClose
        )
        addRenderableWidget(close)
        scrollViewport.updateWidgetPositions()
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

    private fun addActionFlow(
        left: Int,
        right: Int,
        startY: Int,
        specs: List<Pair<UiButtonSpec, () -> Unit>>,
        availableWidth: Int
    ): Int {
        var x = left
        var y = startY
        var rowHeight = 0
        specs.forEach { (spec, press) ->
            var widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec, press = press)
            if (x != left && x + widget.width > right) {
                x = left
                y += rowHeight + 5
                rowHeight = 0
                widget = CobblemonUiButton.create(x, viewportTop + y, availableWidth, spec, press = press)
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

    private fun addScrollingWidget(widget: AbstractWidget, contentY: Int) {
        addWidget(widget)
        scrollingWidgets += ScrollingWidget(widget, contentY)
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
        if (theme.pixelDecorations == null) {
            graphics.drawString(font, title, shellLeft + 12, shellTop + 10, theme.colors.textPrimary, false)
            graphics.drawString(
                font,
                text("subtitle", text("theme.${preset.id}")),
                shellLeft + 12,
                shellTop + 24,
                theme.colors.textSecondary,
                false
            )
        } else {
            drawPixelHeader(graphics)
        }

        scrollViewport.render(graphics, mouseX, mouseY, partialTick) { scrollOffset ->
            sectionY.forEach { (key, contentY) ->
                graphics.drawString(
                    font,
                    text(key),
                    shellLeft + 12,
                    viewportTop + contentY - scrollOffset,
                    theme.colors.borderBright,
                    false
                )
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick)

        tooltipTarget?.takeIf { it.visible && (it.isHovered || it.isFocused) }?.let { target ->
            CobblemonUiTooltipRenderer.render(
                graphics,
                UiTooltipSpec(text("tooltip_title"), text("tooltip_body"), UiOverlayTone.INFO),
                UiRect(target.x, target.y, target.width, target.height),
                width,
                height
            )
        }
        toastLayer.render(graphics, width, height)
    }

    private fun drawPixelHeader(graphics: GuiGraphics) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val pixels = checkNotNull(theme.pixelDecorations)
        val left = shellLeft + 6
        val top = shellTop + 6
        val right = shellLeft + shellWidth - 6
        graphics.fill(left, top, right, top + 28, pixels.titleBar)
        graphics.fill(left, top + 27, right, top + 28, pixels.titleBarShade)
        repeat(4) { row ->
            var x = left + (row and 1)
            while (x < right) {
                graphics.fill(x, top + row, x + 1, top + row + 1, if ((x + row) % 2 == 0) pixels.ditherLight else pixels.ditherDark)
                x += 2
            }
        }
        graphics.drawString(font, title, left + 6, top + 7, theme.colors.textPrimary, false)
        graphics.drawString(
            font,
            text("subtitle", text("theme.${preset.id}")),
            left + 6,
            top + 18,
            theme.colors.textSecondary,
            false
        )
    }

    private fun customSurfaceOverrides(): UiSurfaceOverrides =
        if (preset == UiThemePreset.PIXEL_LEAGUE) {
            UiSurfaceOverrides(
                shape = UiShape.Rectangle,
                fill = UiFill.Solid(0xFF8B633F.toInt()),
                border = UiBorder.None,
                backgroundOpacity = 0.82f
            )
        } else {
            UiSurfaceOverrides(
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
        }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollViewport.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    internal fun openDemoDialog() {
        minecraft?.setScreen(
            CobblemonUiDialogScreen(
                this,
                UiDialogSpec(
                    text("dialog_title"),
                    text("dialog_message"),
                    text("confirm"),
                    text("cancel"),
                    UiOverlayTone.WARNING
                ),
                confirm = { toastLayer.show(UiToastSpec(text("toast_confirmed"), UiOverlayTone.SUCCESS)) }
            )
        )
    }

    companion object {
        private fun text(suffix: String, vararg args: Any): Component =
            Component.translatable("screen.cobblemon_ui_kit.gallery.$suffix", *args)
    }
}
