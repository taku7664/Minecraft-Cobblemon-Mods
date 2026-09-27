package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiListItemSpec
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.UiPanelSpec
import jbro.cobblemon.uikit.UiPanelTone
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiScrollState
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiDialogScreen
import jbro.cobblemon.uikit.client.CobblemonUiListItem
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.CobblemonUiPanel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * The pieces every embedded hub tab is built from, so each content reads as part of the same hub at any size:
 * a summary strip, raised cards with a title band, choice rows and a footer of actions. Everything is laid out
 * from the rectangle it is given; nothing assumes where in the content area it sits.
 */
object MccHubKit {
    /** Space between neighbouring pieces. */
    const val GAP = 3

    /** Height of a summary strip. */
    const val STRIP_HEIGHT = 20

    /** A medium Pixel League control. */
    const val CONTROL_HEIGHT = 26

    /** A footer: a rule, then one row of medium controls with a little room around it. */
    const val FOOTER_HEIGHT = CONTROL_HEIGHT + 4

    /** Rows a card spends on its frame and title band above and below its body. */
    const val CARD_CHROME_HEIGHT = 27

    private const val TITLE_BAND_HEIGHT = 15

    enum class CardTone {
        /** The card the tab is about: a warm title band. */
        FEATURE,

        /** Supporting information: a neutral title band. */
        INFO,
    }

    /** One footer control. [minWidth] widens a short label, typically the tab's primary action. */
    class Action(
        val label: Component,
        val variant: UiButtonVariant = UiButtonVariant.SECONDARY,
        val enabled: Boolean = true,
        val tooltip: Component? = null,
        val minWidth: Int = 0,
        val press: () -> Unit,
    )

    class Choice(val id: String, val label: Component)

    /** Adds a raised card titled [title] over [rect] and returns the body inside its frame. */
    fun card(
        host: MccHubContentHost,
        rect: UiRect,
        title: Component,
        tone: CardTone = CardTone.INFO,
        icon: CobblemonUiRenderContent? = null,
    ): UiRect {
        host.add(CobblemonUiPanel.create(rect.x, rect.y, rect.width, rect.height, UiPanelSpec(tone = UiPanelTone.RAISED)))
        host.add(TitleBand(rect, title, tone, icon))
        return cardBody(rect)
    }

    /** The body [card] would return for [rect], for layouts that size a card around its content. */
    fun cardBody(rect: UiRect): UiRect = UiRect(
        rect.x + 6,
        rect.y + 2 + TITLE_BAND_HEIGHT + 5,
        (rect.width - 12).coerceAtLeast(1),
        (rect.height - CARD_CHROME_HEIGHT).coerceAtLeast(1),
    )

    /**
     * A strip across [rect]: an optional item icon and [start] on the left, [end] right-aligned. [progress]
     * (done, total) draws that many small segments just before [end], for a set or a series.
     */
    fun strip(
        host: MccHubContentHost,
        rect: UiRect,
        start: Component,
        end: Component? = null,
        icon: ItemStack? = null,
        progress: Pair<Int, Int>? = null,
    ) {
        host.add(Strip(rect, start, end, icon, progress))
    }

    /**
     * A footer across [rect]: a rule, [start] actions from the left and [end] actions packed against the right.
     * End actions keep their natural width; start actions share what is left.
     */
    fun footer(host: MccHubContentHost, rect: UiRect, start: List<Action>, end: List<Action>) {
        host.add(Rule(rect))
        val y = rect.y + (rect.height - CONTROL_HEIGHT + 1) / 2 + 1
        var right = rect.right
        end.asReversed().forEach { action ->
            val button = button(action, 0, y, (rect.width / 2).coerceAtLeast(40))
            button.x = right - button.width
            right = button.x - 4
            host.add(button)
        }
        var left = rect.x
        start.forEach { action ->
            val room = right - left - 4
            if (room < 24) return@forEach
            val button = host.add(button(action, left, y, room))
            left += button.width + 4
        }
    }

    /** Height of a titled choice row: a title line above one row of controls. */
    const val STACKED_CHOICE_HEIGHT = CONTROL_HEIGHT + 12

    /**
     * One setting of a [choices] group. A row with [selectedIds] picks any number of options: pressing one reports
     * it so the tab can toggle it, and its options are always shown since they cannot collapse to one control.
     */
    class ChoiceRow(
        val title: Component,
        val options: List<Choice>,
        val selectedId: String?,
        val enabled: Boolean,
        val tooltip: Component? = null,
        val selectedIds: Set<String>? = null,
        val select: (String) -> Unit,
    ) {
        val multiple: Boolean get() = selectedIds != null

        init {
            require(options.isNotEmpty()) { "A hub choice needs options" }
        }
    }

    /**
     * Settings stacked down [rect], laid out as one group so every row's controls start at the same x:
     * - when every row fits beside its title, the titles share one label column and all options follow it;
     * - otherwise, when [rect] is tall enough, each title sits on a line above its row and the controls start at
     *   the left edge, with all options shown or, when they do not fit, one control that moves to the next option;
     * - otherwise every row is one such control reading "title: choice".
     * Returns the y just below the last row.
     */
    fun choices(host: MccHubContentHost, rect: UiRect, rows: List<ChoiceRow>): Int {
        if (rows.isEmpty()) return rect.y
        val font = Minecraft.getInstance().font
        val labelWidth = rows.maxOf { font.width(it.title) } + 8
        fun natural(row: ChoiceRow) = row.options.sumOf { font.width(it.label) + 20 } + (row.options.size - 1) * 2
        val inline = inlineChoices(rect.width, rows)
        val titled = !inline && rect.height >= rows.size * (STACKED_CHOICE_HEIGHT + GAP) - GAP
        var y = rect.y
        rows.forEach { row ->
            when {
                inline -> {
                    host.add(Label(UiRect(rect.x, y, labelWidth, CONTROL_HEIGHT), row.title))
                    options(host, UiRect(rect.x + labelWidth, y, rect.width - labelWidth, CONTROL_HEIGHT), row)
                    y += CONTROL_HEIGHT + GAP
                }
                titled -> {
                    host.add(Label(UiRect(rect.x, y, rect.width, 10), row.title))
                    val controls = UiRect(rect.x, y + 12, rect.width, CONTROL_HEIGHT)
                    if (row.multiple || natural(row) <= rect.width) options(host, controls, row) else cycle(host, controls, row, row.currentLabel())
                    y += STACKED_CHOICE_HEIGHT + GAP
                }
                // Without a title line a pick-any row keeps all its options and names itself in their tooltip.
                row.multiple -> {
                    options(host, UiRect(rect.x, y, rect.width, CONTROL_HEIGHT), row, row.tooltip ?: row.title)
                    y += CONTROL_HEIGHT + GAP
                }
                else -> {
                    val label = Component.empty().append(row.title).append(Component.literal(": ")).append(row.currentLabel())
                    cycle(host, UiRect(rect.x, y, rect.width, CONTROL_HEIGHT), row, label)
                    y += CONTROL_HEIGHT + GAP
                }
            }
        }
        return y - GAP
    }

    enum class ChoiceMode {
        /** Every row fits beside one shared label column. */
        INLINE,

        /** Each row's title sits on its own line above its controls. */
        TITLED,

        /** Each row is one control reading "title: choice". */
        COMPACT,
    }

    /** How [choices] would lay [rows] out across [width] with [availableHeight] to spare. */
    fun choiceMode(width: Int, availableHeight: Int, rows: List<ChoiceRow>): ChoiceMode = when {
        rows.isEmpty() || inlineChoices(width, rows) -> ChoiceMode.INLINE
        availableHeight >= rows.size * (STACKED_CHOICE_HEIGHT + GAP) - GAP -> ChoiceMode.TITLED
        else -> ChoiceMode.COMPACT
    }

    /**
     * [actions] as equally wide controls side by side across [rect], for settings that toggle on press. A tight
     * area passes [UiControlSize.SMALL]; [controlHeight] tells how tall a row of that size is.
     */
    fun buttonRow(host: MccHubContentHost, rect: UiRect, actions: List<Action>, size: UiControlSize = UiControlSize.MEDIUM) {
        if (actions.isEmpty()) return
        val width = (rect.width - (actions.size - 1) * 2) / actions.size
        actions.forEachIndexed { index, action ->
            val button = CobblemonUiButton.create(rect.x + index * (width + 2), rect.y, width,
                UiButtonSpec(fitted(action.label, width - 12), variant = action.variant, size = size,
                    width = UiWidthPolicy.Fixed(width)), press = action.press)
            button.active = action.enabled
            action.tooltip?.let { button.setTooltip(Tooltip.create(it)) }
            host.add(button)
        }
    }

    /**
     * [entries] as rows that scroll inside [rect] under the mouse wheel. The offset lives with the tab ([offset] in,
     * [offsetChanged] out); the tab forwards its wheel events to the returned [Scrollable].
     */
    fun scrollList(
        host: MccHubContentHost,
        rect: UiRect,
        entries: List<ListEntry>,
        offset: Int,
        size: UiControlSize = UiControlSize.MEDIUM,
        offsetChanged: (Int) -> Unit,
    ): Scrollable = host.add(ScrollList(rect, entries, offset, size, offsetChanged))

    /** The height of one control of [size] in the current theme. */
    fun controlHeight(size: UiControlSize): Int = CobblemonUiThemes.registry.snapshot().metrics(size).height

    /** The height [choices] would take for [rows] across [width] with [availableHeight] to spare. */
    fun choicesHeight(width: Int, availableHeight: Int, rows: List<ChoiceRow>): Int {
        if (rows.isEmpty()) return 0
        val titled = !inlineChoices(width, rows) && availableHeight >= rows.size * (STACKED_CHOICE_HEIGHT + GAP) - GAP
        return rows.size * ((if (titled) STACKED_CHOICE_HEIGHT else CONTROL_HEIGHT) + GAP) - GAP
    }

    private fun inlineChoices(width: Int, rows: List<ChoiceRow>): Boolean {
        val font = Minecraft.getInstance().font
        val labelWidth = rows.maxOf { font.width(it.title) } + 8
        return rows.all { row -> labelWidth + row.options.sumOf { font.width(it.label) + 20 } + (row.options.size - 1) * 2 <= width }
    }

    private fun ChoiceRow.currentLabel(): Component = options.firstOrNull { it.id == selectedId }?.label ?: Component.literal("-")

    private fun options(host: MccHubContentHost, rect: UiRect, row: ChoiceRow, tooltip: Component? = row.tooltip) {
        val width = (rect.width - (row.options.size - 1) * 2) / row.options.size
        row.options.forEachIndexed { index, option ->
            val selected = row.selectedIds?.contains(option.id) ?: (option.id == row.selectedId)
            val button = CobblemonUiButton.create(rect.x + index * (width + 2), rect.y, width,
                UiButtonSpec(fitted(option.label, width - 8), variant = UiButtonVariant.SECONDARY, size = UiControlSize.MEDIUM,
                    width = UiWidthPolicy.Fixed(width), selected = selected)) { if (row.multiple || !selected) row.select(option.id) }
            button.active = row.enabled
            tooltip?.let { button.setTooltip(Tooltip.create(it)) }
            host.add(button)
        }
    }

    private fun cycle(host: MccHubContentHost, rect: UiRect, row: ChoiceRow, label: Component) {
        val index = row.options.indexOfFirst { it.id == row.selectedId }
        val button = CobblemonUiButton.create(rect.x, rect.y, rect.width,
            UiButtonSpec(fitted(label, rect.width - 16), variant = UiButtonVariant.SECONDARY, size = UiControlSize.MEDIUM,
                width = UiWidthPolicy.Fixed(rect.width))) {
            row.select(row.options[(index + 1).mod(row.options.size)].id)
        }
        button.active = row.enabled && row.options.size > 1
        row.tooltip?.let { button.setTooltip(Tooltip.create(it)) }
        host.add(button)
    }

    /** A small control at the end of a list row, such as a quantity step. */
    class RowAction(val label: Component, val enabled: Boolean = true, val press: () -> Unit)

    /**
     * One row of a [pagedList]. An [icon] is drawn by the UI kit at the row's start; [actions] sit at its end and
     * take their own clicks, while a press anywhere else on the row runs [press].
     */
    class ListEntry(
        val title: Component,
        val supporting: Component? = null,
        val trailing: Component? = null,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val tooltip: Component? = null,
        val icon: CobblemonUiRenderContent? = null,
        val actions: List<RowAction> = emptyList(),
        val press: () -> Unit,
    )

    /**
     * [entries] as full-width list rows down [rect], a page at a time. When they overflow, the bottom line holds
     * a page switcher; [page] comes from the tab and [pageChanged] reports a switch. [empty] fills an empty list.
     */
    fun pagedList(
        host: MccHubContentHost,
        rect: UiRect,
        entries: List<ListEntry>,
        page: Int,
        empty: Component,
        pageChanged: (Int) -> Unit,
    ) {
        if (entries.isEmpty()) {
            placeholder(host, rect, empty)
            return
        }
        val metrics = CobblemonUiThemes.registry.snapshot().metrics(UiControlSize.MEDIUM)
        val step = (if (entries.any { it.supporting != null }) metrics.supportingHeight else metrics.height) + 2
        var perPage = ((rect.height + 2) / step).coerceAtLeast(1)
        if (entries.size > perPage) perPage = ((rect.height - CONTROL_HEIGHT - GAP + 2) / step).coerceAtLeast(1)
        val pages = (entries.size + perPage - 1) / perPage
        val current = page.coerceIn(0, pages - 1)
        entries.drop(current * perPage).take(perPage).forEachIndexed { index, entry ->
            val item = if (entry.icon == null && entry.actions.isEmpty()) {
                CobblemonUiListItem.create(rect.x, rect.y + index * step, rect.width,
                    UiListItemSpec(fitted(entry.title, rect.width - 40), entry.supporting?.let { fitted(it, rect.width - 20) },
                        trailingText = entry.trailing, selected = entry.selected), entry.press)
            } else {
                Row(UiRect(rect.x, rect.y + index * step, rect.width, step - 2), entry)
            }
            item.active = entry.enabled
            entry.tooltip?.let { item.setTooltip(Tooltip.create(it)) }
            host.add(item)
        }
        if (pages <= 1) return
        val y = rect.bottom - CONTROL_HEIGHT
        val previous = CobblemonUiButton.create(rect.x, y, 40, UiButtonSpec(Component.literal("<"), size = UiControlSize.MEDIUM,
            width = UiWidthPolicy.Fixed(40))) { pageChanged(current - 1) }
        previous.active = current > 0
        host.add(previous)
        val next = CobblemonUiButton.create(rect.right - 40, y, 40, UiButtonSpec(Component.literal(">"), size = UiControlSize.MEDIUM,
            width = UiWidthPolicy.Fixed(40))) { pageChanged(current + 1) }
        next.active = current < pages - 1
        host.add(next)
        host.add(PageLabel(UiRect(rect.x + 44, y, rect.width - 88, CONTROL_HEIGHT), Component.literal("${current + 1} / $pages")))
    }

    /** A quiet centered line for a tab still waiting for its server state, or with nothing to show. */
    fun placeholder(host: MccHubContentHost, rect: UiRect, text: Component) {
        host.add(Placeholder(rect, text))
    }

    /**
     * Asks before an irreversible action in a dialog over the hub; the hub comes back when it closes.
     * [confirm] runs only on the confirming button.
     */
    fun confirm(title: Component, body: Component, confirmLabel: Component, backLabel: Component, confirm: () -> Unit) {
        val client = Minecraft.getInstance()
        val parent = client.screen ?: return
        client.setScreen(CobblemonUiDialogScreen(parent,
            UiDialogSpec(title, body, confirmLabel, backLabel, UiOverlayTone.DANGER),
            confirm = confirm,
            themeOverride = MccHubTheme.snapshot()))
    }

    /** A titled paragraph of a [document]. */
    class Section(val title: Component?, val body: Component)

    /** Something in a tab that scrolls under the mouse wheel; the tab forwards its wheel events here. */
    fun interface Scrollable {
        fun scroll(mouseX: Double, mouseY: Double, delta: Double): Boolean
    }

    /**
     * Wrapped [sections] that scroll inside [rect], with a thin bar when they overflow. The offset lives with the
     * tab ([offset] in, [offsetChanged] out) so a rebuild keeps the reading position.
     */
    fun document(
        host: MccHubContentHost,
        rect: UiRect,
        sections: List<Section>,
        offset: Int,
        offsetChanged: (Int) -> Unit,
    ): Scrollable = host.add(Document(rect, sections, offset, offsetChanged))

    /** Wrapped text inside [rect]; lines that do not fit are dropped from the end. */
    fun text(host: MccHubContentHost, rect: UiRect, text: Component, color: (UiThemeSnapshot) -> Int = ::panelText) {
        host.add(TextBlock(rect, text, color))
    }

    /** The text colour for a raised card body. */
    fun panelText(theme: UiThemeSnapshot): Int = theme.surfaces.panelAltText ?: theme.colors.textPrimary

    /** [text] cut to [width] pixels, for labels whose room is decided by the layout. */
    fun fitted(text: Component, width: Int): Component {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= width) return text
        return Component.literal(font.plainSubstrByWidth(text.string, (width - font.width("…")).coerceAtLeast(0)) + "…")
    }

    /** Splits [rect] into columns whose widths follow [weights], [GAP] apart. */
    fun columns(rect: UiRect, vararg weights: Int): List<UiRect> {
        val total = weights.sum().coerceAtLeast(1)
        val room = rect.width - GAP * (weights.size - 1)
        var x = rect.x
        return weights.mapIndexed { index, weight ->
            val width = if (index == weights.lastIndex) rect.right - x else room * weight / total
            UiRect(x, rect.y, width.coerceAtLeast(1), rect.height).also { x += width + GAP }
        }
    }

    private fun button(action: Action, x: Int, y: Int, availableWidth: Int): CobblemonUiButton {
        val font = Minecraft.getInstance().font
        val width = if (action.minWidth > font.width(action.label) + 24) {
            UiWidthPolicy.Fixed(action.minWidth.coerceAtMost(availableWidth))
        } else {
            UiWidthPolicy.Content
        }
        val button = CobblemonUiButton.create(x, y, availableWidth,
            UiButtonSpec(action.label, variant = action.variant, size = UiControlSize.MEDIUM, width = width), press = action.press)
        button.active = action.enabled
        action.tooltip?.let { button.setTooltip(Tooltip.create(it)) }
        return button
    }

    private class TitleBand(
        private val card: UiRect,
        private val title: Component,
        private val tone: CardTone,
        private val icon: CobblemonUiRenderContent?,
    ) : AbstractWidget(card.x, card.y, card.width, TITLE_BAND_HEIGHT + 2, title) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val band = when (tone) {
                CardTone.FEATURE -> theme.colors.accentCaution
                CardTone.INFO -> theme.colors.borderBright
            }
            graphics.fill(card.x + 2, card.y + 2, card.right - 2, card.y + 2 + TITLE_BAND_HEIGHT, band)
            var left = card.x + 7
            if (icon != null) {
                CobblemonUiRenderSlot.drawContent(graphics, UiRect(card.x + 4, card.y + 3, 13, 13), icon, partialTick)
                left = card.x + 20
            }
            drawLine(graphics, title, left, card.y + 5, card.right - 7 - left, theme.colors.shell)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, title)
        }
    }

    private class Strip(
        private val rect: UiRect,
        private val start: Component,
        private val end: Component?,
        private val icon: ItemStack?,
        private val progress: Pair<Int, Int>?,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, start) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            graphics.fill(rect.x, rect.y, rect.right, rect.bottom, theme.pixelDecorations?.titleBar ?: theme.colors.panel)
            graphics.fill(rect.x, rect.bottom - 1, rect.right, rect.bottom, theme.colors.border)
            val textY = rect.y + (rect.height - 1 - font.lineHeight) / 2 + 1
            var left = rect.x + 6
            if (icon != null) {
                graphics.renderItem(icon, rect.x + 3, rect.y + (rect.height - 16) / 2)
                left = rect.x + 23
            }
            var right = rect.right - 6
            end?.let {
                right -= font.width(it)
                graphics.drawString(font, it, right, textY, theme.colors.textPrimary, false)
                right -= 6
            }
            progress?.let { (done, total) ->
                val segment = 7
                right -= total * (segment + 2) - 2
                (0 until total).forEach { index ->
                    val x = right + index * (segment + 2)
                    val top = rect.y + (rect.height - 1 - 6) / 2
                    graphics.fill(x, top, x + segment, top + 6, theme.colors.border)
                    graphics.fill(x + 1, top + 1, x + segment - 1, top + 5,
                        if (index < done) theme.colors.accentCaution else theme.colors.shell)
                }
                right -= 6
            }
            drawLine(graphics, start, left, textY, right - left, theme.colors.textPrimary)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, start)
        }
    }

    private class Label(private val rect: UiRect, private val label: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, label) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val y = if (rect.height <= font.lineHeight + 2) rect.y else rect.y + (rect.height - font.lineHeight) / 2 + 1
            drawLine(graphics, label, rect.x, y, rect.width, panelText(theme))
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, label)
        }
    }

    private class TextBlock(
        private val rect: UiRect,
        private val text: Component,
        private val color: (UiThemeSnapshot) -> Int,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val lineHeight = font.lineHeight + 1
            val colour = color(CobblemonUiThemes.registry.snapshot())
            font.split(text, rect.width.coerceAtLeast(1)).take((rect.height / lineHeight).coerceAtLeast(1))
                .forEachIndexed { index, line -> graphics.drawString(font, line, rect.x, rect.y + index * lineHeight, colour, false) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, text)
        }
    }

    private class Document(
        private val rect: UiRect,
        sections: List<Section>,
        offset: Int,
        private val offsetChanged: (Int) -> Unit,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()), Scrollable {
        private class Line(val text: net.minecraft.util.FormattedCharSequence, val title: Boolean, val gapBefore: Int)

        private val lines: List<Line>
        private val scroll: UiScrollState

        init {
            active = false
            val font = Minecraft.getInstance().font
            val width = (rect.width - 6).coerceAtLeast(1)
            lines = buildList {
                sections.forEachIndexed { index, section ->
                    val gap = if (index == 0) 0 else 6
                    section.title?.let { title -> font.split(title, width).forEachIndexed { line, text -> add(Line(text, true, if (line == 0) gap else 0)) } }
                    font.split(section.body, width).forEachIndexed { line, text ->
                        add(Line(text, false, if (line == 0 && section.title == null) gap else 0))
                    }
                }
            }
            scroll = UiScrollState(rect.height, lines.sumOf { it.gapBefore + LINE_HEIGHT }, LINE_HEIGHT * 3).also { it.jumpTo(offset) }
        }

        override fun scroll(mouseX: Double, mouseY: Double, delta: Double): Boolean {
            if (mouseX < rect.x || mouseX >= rect.right || mouseY < rect.y || mouseY >= rect.bottom) return false
            val moved = scroll.scroll(delta)
            if (moved) offsetChanged(scroll.offset)
            return moved
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            graphics.enableScissor(rect.x, rect.y, rect.right, rect.bottom)
            try {
                var y = rect.y - scroll.offset
                lines.forEach { line ->
                    y += line.gapBefore
                    if (y + LINE_HEIGHT >= rect.y && y <= rect.bottom) {
                        graphics.drawString(font, line.text, rect.x, y,
                            if (line.title) theme.colors.accentPrimary else panelText(theme), false)
                    }
                    y += LINE_HEIGHT
                }
            } finally {
                graphics.disableScissor()
            }
            if (scroll.maxOffset > 0) {
                graphics.fill(rect.right - 2, rect.y, rect.right, rect.bottom, theme.colors.border)
                val thumb = scroll.thumb(rect.y, rect.height)
                graphics.fill(rect.right - 2, thumb.start, rect.right, thumb.endExclusive, theme.colors.accentPrimary)
            }
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

        private companion object {
            const val LINE_HEIGHT = 10
        }
    }

    /** A list row with a UI kit icon and trailing actions, styled like the UI kit's own list items. */
    private class Row(private val rect: UiRect, private val entry: ListEntry) :
        AbstractButton(rect.x, rect.y, rect.width, rect.height, entry.title) {
        private val actionRects = actionRects(rect, entry)

        /** Keyboard activation and clicks outside the actions press the row itself. */
        override fun onPress() = entry.press()

        override fun onClick(mouseX: Double, mouseY: Double) {
            val index = actionRects.indexOfFirst { it.contains(mouseX, mouseY) }
            if (index < 0) {
                onPress()
                return
            }
            val action = entry.actions[index]
            if (action.enabled) action.press()
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
            drawEntry(graphics, rect, entry, active, isHovered, mouseX, mouseY, partialTick)
    }

    /**
     * [entries] as rows that scroll inside [rect] under the mouse wheel, clipped at its edges, with a thin bar when
     * they overflow. A click goes to the row under it, or to that row's action.
     */
    private class ScrollList(
        private val rect: UiRect,
        private val entries: List<ListEntry>,
        offset: Int,
        size: UiControlSize,
        private val offsetChanged: (Int) -> Unit,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()), Scrollable {
        private val rowHeight = CobblemonUiThemes.registry.snapshot().metrics(size).let { metrics ->
            if (entries.any { it.supporting != null }) metrics.supportingHeight else metrics.height
        }
        private val step = rowHeight + 2
        private val scroll = UiScrollState(rect.height, entries.size * step - 2, step).also { it.jumpTo(offset) }
        private val rowWidth = if (scroll.maxOffset > 0) rect.width - 5 else rect.width

        private fun rowBounds(index: Int) = UiRect(rect.x, rect.y + index * step - scroll.offset, rowWidth, rowHeight)

        override fun scroll(mouseX: Double, mouseY: Double, delta: Double): Boolean {
            if (!rect.contains(mouseX, mouseY)) return false
            val moved = scroll.scroll(delta)
            if (moved) offsetChanged(scroll.offset)
            return moved
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button != 0 || !rect.contains(mouseX, mouseY)) return false
            val index = entries.indices.firstOrNull { rowBounds(it).contains(mouseX, mouseY) } ?: return false
            val entry = entries[index]
            if (!entry.enabled) return false
            playDownSound(Minecraft.getInstance().soundManager)
            val action = actionRects(rowBounds(index), entry).indexOfFirst { it.contains(mouseX, mouseY) }
            if (action < 0) entry.press() else if (entry.actions[action].enabled) entry.actions[action].press()
            return true
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.enableScissor(rect.x, rect.y, rect.right, rect.bottom)
            try {
                entries.forEachIndexed { index, entry ->
                    val bounds = rowBounds(index)
                    if (bounds.bottom < rect.y || bounds.y > rect.bottom) return@forEachIndexed
                    val hovered = rect.contains(mouseX.toDouble(), mouseY.toDouble()) && bounds.contains(mouseX.toDouble(), mouseY.toDouble())
                    drawEntry(graphics, bounds, entry, entry.enabled, hovered, mouseX, mouseY, partialTick)
                }
            } finally {
                graphics.disableScissor()
            }
            if (scroll.maxOffset > 0) {
                graphics.fill(rect.right - 3, rect.y, rect.right, rect.bottom, theme.colors.border)
                val thumb = scroll.thumb(rect.y, rect.height)
                graphics.fill(rect.right - 3, thumb.start, rect.right, thumb.endExclusive, theme.colors.accentPrimary)
            }
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** The page switcher's "page / pages", on one line between its arrows. */
    private class PageLabel(private val rect: UiRect, private val text: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val line = fitted(text, rect.width)
            graphics.drawString(font, line, rect.x + (rect.width - font.width(line)) / 2, rect.y + (rect.height - font.lineHeight) / 2 + 1,
                CobblemonUiThemes.registry.snapshot().colors.textDim, false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, text)
        }
    }

    private class Placeholder(private val rect: UiRect, private val text: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val lines = font.split(text, (rect.width - 16).coerceAtLeast(1))
            val top = rect.y + (rect.height - lines.size * (font.lineHeight + 1)) / 2
            lines.forEachIndexed { index, line ->
                graphics.drawString(font, line, rect.x + (rect.width - font.width(line)) / 2,
                    top + index * (font.lineHeight + 1), theme.colors.textDim, false)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) {
            output.add(NarratedElementType.TITLE, text)
        }
    }

    private class Rule(private val rect: UiRect) : AbstractWidget(rect.x, rect.y, rect.width, 1, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            graphics.fill(rect.x, rect.y, rect.right, rect.y + 1, CobblemonUiThemes.registry.snapshot().colors.borderBright)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private fun UiRect.contains(mouseX: Double, mouseY: Double): Boolean = mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom

    private fun actionRects(bounds: UiRect, entry: ListEntry): List<UiRect> = entry.actions.indices.map { index ->
        val size = (bounds.height - 6).coerceAtMost(18)
        UiRect(bounds.right - 4 - (entry.actions.size - index) * (size + 2) + 2, bounds.y + (bounds.height - size) / 2, size, size)
    }

    /** One list entry as a UI kit list row: icon, title and supporting line, trailing text and actions. */
    private fun drawEntry(
        graphics: GuiGraphics,
        bounds: UiRect,
        entry: ListEntry,
        enabled: Boolean,
        hovered: Boolean,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val font = Minecraft.getInstance().font
        val state = when {
            !enabled -> UiWidgetState.DISABLED
            hovered -> UiWidgetState.HOVER
            entry.selected -> UiWidgetState.SELECTED
            else -> UiWidgetState.NORMAL
        }
        val style = theme.style(UiButtonVariant.SECONDARY, state)
        UiSurfaceRenderer.draw(graphics, bounds.x, bounds.y, bounds.width, bounds.height, style.surface)
        var left = bounds.x + 6
        entry.icon?.let { icon ->
            val size = (bounds.height - 6).coerceAtMost(16)
            CobblemonUiRenderSlot.drawContent(graphics, UiRect(left, bounds.y + (bounds.height - size) / 2, size, size), icon, partialTick)
            left += size + 5
        }
        val actions = actionRects(bounds, entry)
        var right = (actions.firstOrNull()?.x ?: (bounds.right - 2)) - 4
        actions.forEachIndexed { index, action ->
            val row = entry.actions[index]
            val actionStyle = theme.style(UiButtonVariant.SECONDARY, when {
                !enabled || !row.enabled -> UiWidgetState.DISABLED
                action.contains(mouseX.toDouble(), mouseY.toDouble()) -> UiWidgetState.HOVER
                else -> UiWidgetState.NORMAL
            })
            UiSurfaceRenderer.draw(graphics, action.x, action.y, action.width, action.height, actionStyle.surface)
            graphics.drawString(font, row.label, action.x + (action.width - font.width(row.label) + 1) / 2,
                action.y + (action.height - font.lineHeight) / 2 + 1, actionStyle.text, false)
        }
        entry.trailing?.let { trailing ->
            val width = font.width(trailing)
            graphics.drawString(font, trailing, right - width, bounds.y + (bounds.height - font.lineHeight) / 2 + 1, style.supportingText, false)
            right -= width + 6
        }
        val textWidth = right - left
        if (entry.supporting == null) {
            graphics.drawString(font, fitted(entry.title, textWidth), left, bounds.y + (bounds.height - font.lineHeight) / 2 + 1, style.text, false)
        } else {
            graphics.drawString(font, fitted(entry.title, textWidth), left, bounds.y + 4, style.text, false)
            graphics.drawString(font, fitted(entry.supporting, textWidth), left, bounds.bottom - font.lineHeight - 3, style.supportingText, false)
        }
    }

    private fun drawLine(graphics: GuiGraphics, text: Component, x: Int, y: Int, width: Int, color: Int) {
        if (width <= 0) return
        graphics.drawString(Minecraft.getInstance().font, fitted(text, width), x, y, color, false)
    }
}
