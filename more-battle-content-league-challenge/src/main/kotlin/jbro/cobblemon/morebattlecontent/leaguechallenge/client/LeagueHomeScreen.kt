package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueAction
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

/** Production screen. The gallery/fixture screen is deliberately not reused as domain state. */
internal class LeagueHomeScreen : Screen(copy("title")) {
    private data class Row(val widget: AbstractWidget, val y: Int)
    private val rows = mutableListOf<Row>()
    private lateinit var viewport: CobblemonUiScrollViewport
    private var left = 0
    private var bodyTop = 0
    private var shellWidth = 0
    private val state get() = LeagueHomeController.state

    override fun isPauseScreen() = false
    override fun init() = refresh()

    internal fun refresh() {
        val view = state.view ?: return
        val oldOffset = if (::viewport.isInitialized) viewport.state.offset else 0
        clearWidgets()
        rows.clear()
        shellWidth = (width - 20).coerceIn(180, 540)
        left = (width - shellWidth) / 2
        bodyTop = 60
        addRenderableWidget(CobblemonUiPanel.create(left, 8, shellWidth, (height - 16).coerceAtLeast(100), UiPanelSpec(tone = UiPanelTone.SHELL)))
        addRenderableWidget(CobblemonUiTextBlock.create(left + 10, 16, shellWidth - 20,
            UiTextSpec(Component.translatable(view.nameKey), UiTextTone.PRIMARY, maxLines = 1)))
        val rank = Component.translatable("screen.cobblemon_more_battle_content_league_challenge.home.rank." + view.rank.lowercase(Locale.ROOT))
        addRenderableWidget(CobblemonUiTextBlock.create(left + 10, 33, shellWidth - 20,
            UiTextSpec(copy("summary", rank, view.badges, view.cap, view.bp), UiTextTone.SECONDARY, maxLines = 2)))
        var y = 0
        fun row(widget: AbstractWidget) { rows += Row(widget, y); addWidget(widget) }
        val notice = when {
            view.errorKey != null -> Component.translatable(view.errorKey)
            state.pending -> copy("waiting")
            view.pendingRewards -> copy("rewards_pending")
            view.runChallenge != null -> copy(if (view.awaitingNext) "next_ready" else "battle_active",
                view.runNameKey?.let(Component::translatable) ?: copy("current_trainer"))
            view.champion -> copy("champion")
            else -> copy("rules")
        }
        val callout = CobblemonUiCallout.create(left + 10, bodyTop, shellWidth - 28,
            UiCalloutSpec(if (view.errorKey != null) UiOverlayTone.WARNING else UiOverlayTone.INFO, copy("status"), notice))
        row(callout)
        y += callout.height + 8
        val track = CobblemonUiStepTrack.create(left + 10, bodyTop + y, shellWidth - 28,
            UiStepTrackSpec(view.challenges.take(8).mapIndexed { index, entry ->
                UiStepSpec("gym_$index", Component.translatable(entry.nameKey), when (entry.status) {
                    "CLEARED" -> UiStepState.CLEARED
                    "AVAILABLE" -> UiStepState.AVAILABLE
                    else -> UiStepState.LOCKED
                })
            }, showLabels = false))
        row(track)
        y += track.height + 8
        for (entry in view.challenges) {
            val cardWidth = shellWidth - 28
            val name = Component.translatable(entry.nameKey)
            val title = CobblemonUiTextBlock.create(left + 16, bodyTop + y + 6, cardWidth - 90,
                UiTextSpec(name, UiTextTone.PANEL_ALT, maxLines = 2))
            val rowHeight = maxOf(52, title.height + 26)
            row(CobblemonUiPanel.create(left + 10, bodyTop + y, cardWidth, rowHeight, UiPanelSpec(tone = UiPanelTone.RAISED)))
            rows += Row(title, y + 6); addWidget(title)
            val status = copy("entry", copy("state." + entry.status.lowercase(Locale.ROOT)), entry.unlockCap)
            val detail = CobblemonUiTextBlock.create(left + 16, bodyTop + y + title.height + 10, cardWidth - 90,
                UiTextSpec(status, UiTextTone.PANEL_ALT, maxLines = 2))
            rows += Row(detail, y + title.height + 10); addWidget(detail)
            val selected = state.selectedId == entry.id
            val button = CobblemonUiButton.create(left + shellWidth - 89, bodyTop + y + 12, 61,
                UiButtonSpec(copy(if (selected) "selected" else "select"), variant = if (selected) UiButtonVariant.PRIMARY else UiButtonVariant.SECONDARY,
                    size = UiControlSize.SMALL, selected = selected)) {
                state.select(entry.id)
                refresh()
            }
            button.active = !state.pending
            button.tooltip = Tooltip.create(name)
            rows += Row(button, y + 12); addWidget(button)
            y += rowHeight + 6
        }
        viewport = CobblemonUiScrollViewport(left + 4, bodyTop, shellWidth - 8, (height - bodyTop - 44).coerceAtLeast(20), y)
        rows.forEach { viewport.register(it.widget, it.y) }
        // Restore scroll after server acknowledgements and local selection changes.
        viewport.state.jumpTo(oldOffset)
        viewport.updateWidgetPositions()
        var actionX = left + 10
        val actionWidth = (shellWidth - 34) / 4
        fun action(label: String, enabled: Boolean, variant: UiButtonVariant, press: () -> Unit) {
            val button = CobblemonUiButton.create(actionX, height - 34, actionWidth,
                UiButtonSpec(copy(label), variant = variant, size = UiControlSize.SMALL), press = press)
            button.active = enabled
            addRenderableWidget(button)
            actionX += actionWidth + 4
        }
        val next = view.runChallenge != null
        action(if (next) "next" else "challenge", if (next) state.canNext else state.canStart, UiButtonVariant.PRIMARY) {
            LeagueHomeController.send(if (next) LeagueAction.NEXT else LeagueAction.START)
        }
        action("forfeit", state.canCancel, UiButtonVariant.DANGER) {
            val nonce = view.nonce
            minecraft?.setScreen(CobblemonUiDialogScreen(this,
                UiDialogSpec(copy("forfeit_title"), copy("forfeit_body"), copy("forfeit"), copy("back"), UiOverlayTone.DANGER),
                confirm = { if (state.view?.nonce == nonce) LeagueHomeController.send(LeagueAction.CANCEL) }))
        }
        action("refresh", !state.pending, UiButtonVariant.SECONDARY) { LeagueHomeController.send(LeagueAction.REFRESH) }
        action("close", true, UiButtonVariant.GHOST, ::onClose)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        if (::viewport.isInitialized) viewport.render(graphics, mouseX, mouseY, partialTick)
    }
    override fun mouseScrolled(x: Double, y: Double, sx: Double, sy: Double) = viewport.mouseScrolled(x, y, sx, sy) || super.mouseScrolled(x, y, sx, sy)
    override fun keyPressed(key: Int, scan: Int, modifiers: Int) = viewport.keyPressed(key) || super.keyPressed(key, scan, modifiers)
    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        if (viewport.mouseClicked(x, y, button)) return true
        // Clipped rows must not intercept footer/header clicks.
        if (y < viewport.top || y >= viewport.top + viewport.height) {
            rows.forEach { it.widget.visible = false }
            return try { super.mouseClicked(x, y, button) } finally { viewport.updateWidgetPositions() }
        }
        return super.mouseClicked(x, y, button)
    }
    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double) = viewport.mouseDragged(y, button) || super.mouseDragged(x, y, button, dx, dy)
    override fun mouseReleased(x: Double, y: Double, button: Int) = viewport.mouseReleased(button) || super.mouseReleased(x, y, button)
    override fun onClose() { LeagueHomeController.dismiss(); super.onClose() }

    companion object {
        internal fun copy(key: String, vararg args: Any): Component =
            Component.translatable("screen.cobblemon_more_battle_content_league_challenge.live.$key", *args)
    }
}
