package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueAction
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueChallengeView
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomePresentation
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

/** Production League home: UI Kit presentation over server-authored progression. */
internal class LeagueHomeScreen : Screen(copy("title")) {
    private data class Row(val widget: AbstractWidget, val y: Int)

    private val rows = mutableListOf<Row>()
    private lateinit var viewport: CobblemonUiScrollViewport
    private var previousTheme: UiThemeSnapshot? = null
    private var left = 0
    private var top = 0
    private var shellWidth = 0
    private var shellHeight = 0
    private var bodyTop = 0
    private val state get() = LeagueHomeController.state

    override fun isPauseScreen() = false

    override fun init() {
        if (previousTheme == null) previousTheme = CobblemonUiThemes.registry.snapshot()
        CobblemonUiThemePresets.install(UiThemePreset.PIXEL_LEAGUE)
        refresh()
    }

    internal fun refresh() {
        val view = state.view ?: return
        val oldOffset = if (::viewport.isInitialized) viewport.state.offset else 0
        clearWidgets()
        rows.clear()
        shellWidth = (width - 20).coerceAtMost(560)
        shellHeight = (height - 16).coerceAtMost(320)
        left = (width - shellWidth) / 2
        top = (height - shellHeight) / 2
        bodyTop = top + 45
        val footerTop = top + shellHeight - 31
        val contentLeft = left + 11
        val contentWidth = shellWidth - 27
        val shell = CobblemonUiPanel.create(left, top, shellWidth, shellHeight, UiPanelSpec(tone = UiPanelTone.SHELL))
        addRenderableWidget(shell)
        addRenderableWidget(PixelDecor(left, top, shellWidth, shellHeight))
        addRenderableWidget(CobblemonUiTextBlock.create(left + 11, top + 9, shellWidth - 22,
            UiTextSpec(Component.translatable(view.nameKey), UiTextTone.PRIMARY, maxLines = 1)))

        var y = 0
        fun row(widget: AbstractWidget, offset: Int = y) {
            rows += Row(widget, offset)
            addWidget(widget)
        }
        val rank = Component.translatable("screen.cobblemon_more_battle_content_league_challenge.home.rank." +
            view.rank.lowercase(Locale.ROOT))
        val summary = CobblemonUiTextBlock.create(contentLeft, bodyTop + y, contentWidth,
            UiTextSpec(copy("summary", rank, view.badges, view.cap, view.bp), UiTextTone.SECONDARY, maxLines = 2))
        row(summary)
        y += summary.height + 7

        val presentation = LeagueHomePresentation.from(view, state.selectedId)
        if (presentation.gyms.isNotEmpty()) {
            row(CobblemonUiTextBlock.create(contentLeft, bodyTop + y, contentWidth,
                UiTextSpec(copy("route"), UiTextTone.WARNING, maxLines = 1)))
            y += 13
            val track = CobblemonUiStepTrack.create(contentLeft, bodyTop + y, contentWidth,
                UiStepTrackSpec(presentation.gyms.mapIndexed { index, entry ->
                    UiStepSpec("gym_$index", Component.translatable(entry.nameKey), when {
                        entry.id == view.runChallenge -> UiStepState.ACTIVE
                        entry.status == "CLEARED" -> UiStepState.CLEARED
                        entry.status == "AVAILABLE" -> UiStepState.AVAILABLE
                        else -> UiStepState.LOCKED
                    })
                }, showLabels = false))
            row(track)
            y += track.height + 4
        }

        presentation.focused?.let { entry ->
            val panelHeight = 51
            row(CobblemonUiPanel.create(contentLeft, bodyTop + y, contentWidth, panelHeight,
                UiPanelSpec(tone = UiPanelTone.RAISED)))
            row(CobblemonUiTextBlock.create(contentLeft + 8, bodyTop + y + 7, contentWidth - 16,
                UiTextSpec(copy("selected_challenge"), UiTextTone.PANEL_ALT, maxLines = 1)), y + 7)
            row(CobblemonUiTextBlock.create(contentLeft + 8, bodyTop + y + 21, contentWidth - 16,
                UiTextSpec(Component.translatable(entry.nameKey), UiTextTone.PANEL_ALT, maxLines = 1)), y + 21)
            row(CobblemonUiTextBlock.create(contentLeft + 8, bodyTop + y + 35, contentWidth - 16,
                UiTextSpec(copy("entry", copy("state." + entry.status.lowercase(Locale.ROOT)), entry.unlockCap),
                    UiTextTone.PANEL_ALT, maxLines = 1)), y + 35)
            y += panelHeight + 7
        }

        val notice = when {
            view.errorKey != null -> Component.translatable(view.errorKey)
            state.pending -> copy("waiting")
            view.pendingRewards -> copy("rewards_pending")
            view.runChallenge != null -> copy(if (view.awaitingNext) "next_ready" else "battle_active",
                view.runNameKey?.let(Component::translatable) ?: copy("current_trainer"))
            view.champion -> copy("champion")
            else -> copy("rules")
        }
        val callout = CobblemonUiCallout.create(contentLeft, bodyTop + y, contentWidth,
            UiCalloutSpec(if (view.errorKey != null) UiOverlayTone.WARNING else UiOverlayTone.INFO,
                copy("status"), notice))
        row(callout)
        y += callout.height + 10
        y = addChallengeGroup(presentation.gyms, copy("gyms"), contentLeft, contentWidth, bodyTop, y, ::row)
        if (presentation.finals.isNotEmpty()) {
            y += 5
            y = addChallengeGroup(presentation.finals, copy("finals"), contentLeft, contentWidth, bodyTop, y, ::row)
        }

        viewport = CobblemonUiScrollViewport(left + 5, bodyTop, shellWidth - 10,
            (footerTop - bodyTop - 4).coerceAtLeast(20), y + 5)
        rows.forEach { viewport.register(it.widget, it.y) }
        viewport.state.jumpTo(oldOffset)
        viewport.updateWidgetPositions()

        val footerY = footerTop + 5
        var actionX = left + 11
        fun action(label: String, enabled: Boolean, variant: UiButtonVariant, press: () -> Unit) {
            val button = CobblemonUiButton.create(actionX, footerY, shellWidth - 22,
                UiButtonSpec(copy(label), variant = variant, size = UiControlSize.SMALL), press = press)
            button.active = enabled
            addRenderableWidget(button)
            actionX += button.width + 5
        }
        val next = view.runChallenge != null
        action(if (next) "next" else "challenge", if (next) state.canNext else state.canStart, UiButtonVariant.PRIMARY) {
            LeagueHomeController.send(if (next) LeagueAction.NEXT else LeagueAction.START)
        }
        action("forfeit", state.canCancel, UiButtonVariant.DANGER) {
            val nonce = view.nonce
            minecraft?.setScreen(CobblemonUiDialogScreen(this,
                UiDialogSpec(copy("forfeit_title"), copy("forfeit_body"), copy("forfeit"), copy("back"), UiOverlayTone.DANGER),
                confirm = { if (state.view?.nonce == nonce) LeagueHomeController.send(LeagueAction.CANCEL) },
                themeOverride = CobblemonUiThemePresets.snapshot(UiThemePreset.PIXEL_LEAGUE)))
        }
        action("refresh", !state.pending, UiButtonVariant.SECONDARY) { LeagueHomeController.send(LeagueAction.REFRESH) }
        val close = CobblemonUiButton.create(0, footerY, shellWidth - 22,
            UiButtonSpec(copy("close"), variant = UiButtonVariant.GHOST, size = UiControlSize.SMALL), press = ::onClose)
        close.x = left + shellWidth - close.width - 11
        addRenderableWidget(close)
    }

    private fun addChallengeGroup(
        entries: List<LeagueChallengeView>, title: Component, contentLeft: Int, contentWidth: Int,
        bodyTop: Int, startY: Int, row: (AbstractWidget, Int) -> Unit
    ): Int {
        if (entries.isEmpty()) return startY
        var y = startY
        row(CobblemonUiTextBlock.create(contentLeft, bodyTop + y, contentWidth,
            UiTextSpec(title, UiTextTone.WARNING, maxLines = 1)), y)
        y += 15
        var x = contentLeft
        entries.forEach { entry ->
            val selected = state.selectedId == entry.id
            val button = CobblemonUiButton.create(x, bodyTop + y, contentWidth,
                UiButtonSpec(Component.translatable(entry.nameKey),
                    variant = UiButtonVariant.SECONDARY, size = UiControlSize.MEDIUM,
                    width = UiWidthPolicy.Content, selected = selected)) {
                state.select(entry.id)
                refresh()
            }
            if (x > contentLeft && x + button.width > contentLeft + contentWidth) {
                x = contentLeft
                y += button.height + 5
                button.x = x
                button.y = bodyTop + y
            }
            button.active = !state.pending
            button.tooltip = Tooltip.create(copy("entry", copy("state." + entry.status.lowercase(Locale.ROOT)), entry.unlockCap))
            row(button, y)
            x += button.width + 5
        }
        return y + 31
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        if (::viewport.isInitialized) viewport.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(x: Double, y: Double, sx: Double, sy: Double) =
        (::viewport.isInitialized && viewport.mouseScrolled(x, y, sx, sy)) || super.mouseScrolled(x, y, sx, sy)
    override fun keyPressed(key: Int, scan: Int, modifiers: Int) =
        (::viewport.isInitialized && viewport.keyPressed(key)) || super.keyPressed(key, scan, modifiers)
    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        if (::viewport.isInitialized && viewport.mouseClicked(x, y, button)) return true
        if (::viewport.isInitialized && (y < viewport.top || y >= viewport.top + viewport.height)) {
            rows.forEach { it.widget.visible = false }
            return try { super.mouseClicked(x, y, button) } finally { viewport.updateWidgetPositions() }
        }
        return super.mouseClicked(x, y, button)
    }
    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double) =
        (::viewport.isInitialized && viewport.mouseDragged(y, button)) || super.mouseDragged(x, y, button, dx, dy)
    override fun mouseReleased(x: Double, y: Double, button: Int) =
        (::viewport.isInitialized && viewport.mouseReleased(button)) || super.mouseReleased(x, y, button)

    override fun removed() {
        val original = previousTheme
        if (original != null && CobblemonUiThemes.registry.snapshot().id == UiThemePreset.PIXEL_LEAGUE.id) {
            CobblemonUiThemes.registry.install(original)
        }
        previousTheme = null
    }

    override fun onClose() { LeagueHomeController.dismiss(); super.onClose() }

    private class PixelDecor(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.empty()) {
        init { active = false }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val decor = theme.pixelDecorations ?: return
            graphics.fill(x + 4, y + 4, x + width - 4, y + 33, decor.titleBar)
            graphics.fill(x + 4, y + 33, x + width - 4, y + 35, decor.titleBarShade)
            graphics.fill(x + 5, y + height - 32, x + width - 5, y + height - 31, theme.colors.borderBright)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    companion object {
        internal fun copy(key: String, vararg args: Any): Component =
            Component.translatable("screen.cobblemon_more_battle_content_league_challenge.live.$key", *args)
    }
}
