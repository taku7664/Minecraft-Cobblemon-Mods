package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.MccBattleHubClientState
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiPanelSpec
import jbro.cobblemon.uikit.UiPanelTone
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiPanel
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.text.NumberFormat

/** The battle hub: a left rail of tabs (dashboard, shop, then content mods) around one content area. */
class MccHubScreen(selectedTabId: String = MccHubTabs.DASHBOARD) :
    Screen(hubText("title")), MccHubContentHost {
    var selectedTabId: String = selectedTabId
        private set
    private var previousTheme: UiThemeSnapshot? = null
    private val contents = HashMap<String, MccHubTabContent>()
    private var activeContent: MccHubTabContent? = null
    private var shownContent: MccHubTabContent? = null

    override fun isPauseScreen() = false

    override fun init() {
        if (previousTheme == null) previousTheme = CobblemonUiThemes.registry.snapshot()
        CobblemonUiThemes.registry.install(MccHubTheme.snapshot())
        current = this
        rebuild()
    }

    override fun <T : AbstractWidget> add(widget: T): T = addRenderableWidget(widget)

    override fun rebuild() {
        clearWidgets()
        val layout = MccHubLayout.calculate(width, height, MccHubTabs.all().size)
        addRenderableWidget(
            CobblemonUiPanel.create(layout.shell.x, layout.shell.y, layout.shell.width, layout.shell.height,
                UiPanelSpec(tone = UiPanelTone.SHELL)),
        )
        val badges = headerBadges(layout)
        addRenderableWidget(HubHeader(layout, badges.minOfOrNull { it.x - 6 } ?: layout.balance.x))
        // After the header, whose fill would otherwise cover their labels.
        badges.forEach(::addRenderableWidget)
        addRenderableWidget(RailBackdrop(layout.rail))
        addTabs(layout)
        addRenderableWidget(
            CobblemonUiButton.create(layout.closeButton.x, layout.closeButton.y, layout.closeButton.width,
                UiButtonSpec(hubText("close"), variant = UiButtonVariant.GHOST, size = UiControlSize.MEDIUM,
                    width = UiWidthPolicy.Fixed(layout.closeButton.width))) { onClose() },
        )
        val tab = MccHubTabs.get(selectedTabId) ?: MccHubTabs.all().firstOrNull { it.kind is MccHubTabKind.Embedded }
        val embedded = tab?.kind as? MccHubTabKind.Embedded
        activeContent = embedded?.let { contents.getOrPut(tab.id) { it.create() } }
        if (activeContent !== shownContent) {
            shownContent?.hidden()
            shownContent = activeContent
            activeContent?.shown()
        }
        activeContent?.build(this, layout.content)
    }

    /**
     * Places the content mods' header badges right-aligned before the BP balance, with labels when the brand
     * still fits beside them and as bare icons otherwise.
     */
    private fun headerBadges(layout: MccHubLayout): List<HeaderBadge> {
        val badges = MccHubHeaderBadges.current()
        if (badges.isEmpty()) return emptyList()
        val font = Minecraft.getInstance().font
        val header = layout.header
        val right = layout.balance.x - 10
        val labelled = badges.map { BADGE_ICON + BADGE_ICON_GAP + font.width(it.label) }
        val brandEnd = header.x + 8 + font.width(hubText("brand")) + 14
        val withLabels = right - labelled.sum() - (badges.size - 1) * BADGE_GAP >= brandEnd
        var x = right
        return badges.indices.reversed().map { index ->
            val width = if (withLabels) labelled[index] else BADGE_ICON
            x -= width
            val badge = badges[index]
            HeaderBadge(UiRect(x, header.y + 2, width, header.height - 4), badge, withLabels).also { widget ->
                (badge.tooltip ?: badge.label.takeUnless { withLabels })?.let { widget.setTooltip(Tooltip.create(it)) }
                x -= BADGE_GAP
            }
        }
    }

    /** Switches to [tabId] as if its rail button was pressed; unknown tabs are ignored. */
    fun selectTab(tabId: String) {
        MccHubTabs.get(tabId)?.let(::select)
    }

    private fun addTabs(layout: MccHubLayout) {
        val tabs = MccHubTabs.all().take(layout.visibleTabCount())
        tabs.forEachIndexed { index, tab ->
            val bounds = layout.tabButton(index)
            val denial = tab.accessContentId?.let(MccBattleHubClientState.deniedById::get)
            val button = CobblemonUiButton.create(
                bounds.x,
                bounds.y,
                bounds.width,
                UiButtonSpec(MccHubKit.fitted(tab.label, bounds.width - 16), variant = UiButtonVariant.SECONDARY,
                    size = if (layout.compactTabs) UiControlSize.SMALL else UiControlSize.MEDIUM,
                    width = UiWidthPolicy.Fixed(bounds.width), selected = tab.id == selectedTabId),
                forcedState = if (denial != null) UiWidgetState.DISABLED else null,
            ) { select(tab) }
            if (denial != null) {
                button.setTooltip(Tooltip.create(Component.translatable(denial.reasonKey, *denial.arguments.toTypedArray())))
            }
            addRenderableWidget(button)
        }
    }

    private fun select(tab: MccHubTab) {
        when (val kind = tab.kind) {
            is MccHubTabKind.Embedded -> if (tab.id != selectedTabId) {
                selectedTabId = tab.id
                rebuild()
            }
            is MccHubTabKind.Screen -> kind.open()
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        activeContent?.mouseScrolled(mouseX, mouseY, scrollY) == true || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    override fun removed() {
        if (current === this) current = null
        shownContent?.hidden()
        shownContent = null
        val original = previousTheme
        if (original != null && CobblemonUiThemes.registry.snapshot().id == MccHubTheme.preset.id) {
            CobblemonUiThemes.registry.install(original)
        }
        previousTheme = null
    }

    private class HeaderBadge(private val rect: UiRect, private val badge: MccHubHeaderBadge, private val labelled: Boolean) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, badge.label) {
        init {
            active = false
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            // The header's bottom border takes its last two pixels, so content centres above it.
            val middle = rect.y + (rect.height - 2) / 2 + 1
            CobblemonUiRenderSlot.drawContent(graphics, UiRect(rect.x, middle - BADGE_ICON / 2, BADGE_ICON, BADGE_ICON), badge.icon, partialTick)
            if (labelled) {
                graphics.drawString(font, badge.label, rect.x + BADGE_ICON + BADGE_ICON_GAP, middle - font.lineHeight / 2 + 1,
                    theme.colors.textPrimary, false)
            }
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class HubHeader(private val layout: MccHubLayout, private val brandLimit: Int) : AbstractWidget(
        layout.header.x, layout.header.y, layout.header.width, layout.header.height, Component.empty(),
    ) {
        init {
            active = false
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val header = layout.header
            graphics.fill(header.x, header.y, header.right, header.bottom, theme.pixelDecorations?.titleBar ?: theme.colors.panel)
            graphics.fill(header.x, header.bottom - 2, header.right, header.bottom, theme.colors.border)

            val brand = hubText("brand")
            val brandRoom = (brandLimit - header.x - 14).coerceAtLeast(1)
            val scale = listOf(layout.brandScale, 1.5f, 1f).first { font.width(brand) * it <= brandRoom }
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate((header.x + 8).toDouble(), (header.y + (header.height - 2 - font.lineHeight * scale) / 2.0), 0.0)
            pose.scale(scale, scale, 1f)
            graphics.drawString(font, brand, 0, 0, theme.colors.textPrimary, false)
            pose.popPose()

            val balance = layout.balance
            graphics.fill(balance.x - 5, header.y + 5, balance.x - 4, header.bottom - 6, theme.colors.borderBright)
            val bp = Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.bp",
                NumberFormat.getIntegerInstance().format(MccBattleHubClientState.bpBalance))
            drawFitted(graphics, bp, balance.x + 2, balance.y + (balance.height - font.lineHeight) / 2 + 1,
                balance.width - 4, theme.colors.textPrimary)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class RailBackdrop(private val rail: UiRect) :
        AbstractWidget(rail.x, rail.y, rail.width, rail.height, Component.empty()) {
        init {
            active = false
        }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.fill(rail.x, rail.y, rail.right, rail.bottom, theme.pixelDecorations?.titleBar ?: theme.colors.shell)
            graphics.fill(rail.right - 1, rail.y, rail.right, rail.bottom, theme.colors.borderBright)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    companion object {
        private const val BADGE_ICON = 16
        private const val BADGE_ICON_GAP = 3
        private const val BADGE_GAP = 8

        /** The open hub, so fresh server state can rebuild it in place. */
        var current: MccHubScreen? = null
            private set

        /**
         * Shows the hub on [tabId] with fresh content: opens the hub, switches to the tab, or rebuilds the tab
         * when it is already the one shown.
         */
        fun open(tabId: String) {
            val client = Minecraft.getInstance()
            val hub = current
            when {
                hub == null || client.screen !== hub -> client.setScreen(MccHubScreen(tabId))
                hub.selectedTabId == tabId -> hub.rebuild()
                else -> hub.selectTab(tabId)
            }
        }

        /** Rebuilds the hub if it is the screen and [tabId] is the tab shown, after that tab's state changed. */
        fun refresh(tabId: String) {
            val hub = current ?: return
            if (Minecraft.getInstance().screen === hub && hub.selectedTabId == tabId) hub.rebuild()
        }

        /** Rebuilds the hub if it is the screen, after a header badge changed. */
        fun refreshHeader() {
            val hub = current ?: return
            if (Minecraft.getInstance().screen === hub) hub.rebuild()
        }

        /** Whether the hub is the screen and shows [tabId]. */
        fun showing(tabId: String): Boolean {
            val hub = current ?: return false
            return Minecraft.getInstance().screen === hub && hub.selectedTabId == tabId
        }
    }
}

internal fun hubText(key: String, vararg args: Any): Component =
    Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.hub.$key", *args)

internal fun drawFitted(graphics: GuiGraphics, component: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width <= 0) return
    val font = Minecraft.getInstance().font
    graphics.drawString(font, font.plainSubstrByWidth(component.string, width), x, y, color, false)
}
