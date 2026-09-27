package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueAction
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueChallengeView
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueView
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueDashboardLayout
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueDashboardRect
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomePresentation
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/** The real terminal home. All progression and challenge facts come from the server snapshot. */
internal class LeagueHomeScreen : Screen(copy("title")) {
    private var previousTheme: UiThemeSnapshot? = null
    private val state get() = LeagueHomeController.state

    override fun isPauseScreen() = false

    override fun init() {
        if (previousTheme == null) previousTheme = CobblemonUiThemes.registry.snapshot()
        CobblemonUiThemePresets.install(UiThemePreset.PIXEL_LEAGUE)
        refresh()
    }

    internal fun refresh() {
        val view = state.view ?: return
        clearWidgets()
        val layout = LeagueDashboardLayout.calculate(width, height)
        val presentation = LeagueHomePresentation.from(view, state.selectedId)
        addRenderableWidget(CobblemonUiPanel.create(layout.shell.left, layout.shell.top,
            layout.shell.width, layout.shell.height, UiPanelSpec(tone = UiPanelTone.SHELL)))
        addRenderableWidget(Header(layout, view))
        addRenderableWidget(RouteBackdrop(layout.route))
        val entries = presentation.gyms + presentation.finals
        if (entries.isNotEmpty()) {
            val firstCenter = layout.route.left + 21
            val lastCenter = layout.route.right - 21
            val step = if (entries.size == 1) 44 else (lastCenter - firstCenter) / (entries.size - 1)
            val nodeWidth = (step - 3).coerceIn(36, 80)
            entries.forEachIndexed { index, entry ->
                val center = if (entries.size == 1) (firstCenter + lastCenter) / 2 else
                    firstCenter + (lastCenter - firstCenter) * index / (entries.size - 1)
                val node = RouteNode(center - nodeWidth / 2, layout.route.top + 3, nodeWidth, 37, entry,
                    if (index == presentation.gyms.size) copy("route_league") else Component.translatable(entry.nameKey),
                    if (index == presentation.gyms.size) "L" else (index + 1).toString(),
                    state.selectedId == entry.id, !state.pending) {
                    state.select(entry.id)
                    refresh()
                }
                node.tooltip = Tooltip.create(copy("entry", copy("state." + entry.status.lowercase(Locale.ROOT)), entry.unlockCap))
                addRenderableWidget(node)
            }
        }
        addRenderableWidget(CobblemonUiPanel.create(layout.detail.left, layout.detail.top,
            layout.detail.width, layout.detail.height, UiPanelSpec(tone = UiPanelTone.RAISED)))
        addRenderableWidget(CobblemonUiPanel.create(layout.status.left, layout.status.top,
            layout.status.width, layout.status.height, UiPanelSpec(tone = UiPanelTone.RAISED)))
        addRenderableWidget(ChallengeCard(layout.detail, view, presentation.focused))
        addRenderableWidget(StatusCard(layout.status, view, presentation.focused, state.pending))
        addRenderableWidget(FooterRule(layout.footer))
        addActions(layout, view)
    }

    private fun addActions(layout: LeagueDashboardLayout, view: LeagueView) {
        val y = layout.footer.top + 4
        val left = layout.footer.left + 2
        val primaryWidth = minOf(118, layout.footer.width / 3)
        val primaryX = layout.footer.right - primaryWidth - 2
        val next = view.runChallenge != null
        val primary = CobblemonUiButton.create(primaryX, y, primaryWidth,
            UiButtonSpec(copy(if (next) "next" else "challenge"), variant = UiButtonVariant.PRIMARY,
                size = UiControlSize.SMALL, width = UiWidthPolicy.Fixed(primaryWidth))) {
            LeagueHomeController.send(if (next) LeagueAction.NEXT else LeagueAction.START)
        }
        primary.active = if (next) state.canNext else state.canStart
        addRenderableWidget(primary)

        var x = left
        fun action(key: String, enabled: Boolean, variant: UiButtonVariant, press: () -> Unit) {
            val button = CobblemonUiButton.create(x, y, primaryX - x - 3,
                UiButtonSpec(copy(key), variant = variant, size = UiControlSize.SMALL), press = press)
            button.active = enabled
            addRenderableWidget(button)
            x += button.width + 4
        }
        action("close", true, UiButtonVariant.GHOST, ::onClose)
        action("refresh", !state.pending, UiButtonVariant.SECONDARY) { LeagueHomeController.send(LeagueAction.REFRESH) }
        if (next) action("forfeit", state.canCancel, UiButtonVariant.DANGER) {
            val nonce = view.nonce
            minecraft?.setScreen(CobblemonUiDialogScreen(this,
                UiDialogSpec(copy("forfeit_title"), copy("forfeit_body"), copy("forfeit"), copy("back"), UiOverlayTone.DANGER),
                confirm = { if (state.view?.nonce == nonce) LeagueHomeController.send(LeagueAction.CANCEL) },
                themeOverride = CobblemonUiThemePresets.snapshot(UiThemePreset.PIXEL_LEAGUE)))
        }
    }

    override fun removed() {
        val original = previousTheme
        if (original != null && CobblemonUiThemes.registry.snapshot().id == UiThemePreset.PIXEL_LEAGUE.id) {
            CobblemonUiThemes.registry.install(original)
        }
        previousTheme = null
    }

    override fun onClose() { LeagueHomeController.dismiss(); super.onClose() }

    private class Header(private val layout: LeagueDashboardLayout, private val view: LeagueView) :
        AbstractWidget(layout.header.left, layout.header.top, layout.header.width, layout.header.height, Component.empty()) {
        init { active = false }

        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val header = layout.header
            graphics.fill(header.left, header.top, header.right, header.bottom, theme.pixelDecorations?.titleBar ?: theme.colors.panel)
            graphics.fill(header.left, header.bottom - 2, header.right, header.bottom, theme.colors.border)
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate((header.left + 8).toDouble(), (header.top + 4).toDouble(), 0.0)
            pose.scale(layout.brandScale, layout.brandScale, 1f)
            graphics.drawString(font, copy("brand"), 0, 0, theme.colors.textPrimary, false)
            pose.popPose()
            drawFitted(graphics, Component.translatable(view.nameKey), header.left + 8, header.top + 25,
                layout.face.left - header.left - 15, theme.colors.textSecondary)

            graphics.fill(layout.face.left - 5, header.top + 4, layout.face.left - 4, header.bottom - 4, theme.colors.borderBright)
            graphics.fill(layout.face.left - 2, layout.face.top - 2, layout.face.right + 2, layout.face.bottom + 2, theme.colors.borderBright)
            graphics.fill(layout.face.left, layout.face.top, layout.face.right, layout.face.bottom, theme.colors.panelAlt)
            val client = Minecraft.getInstance()
            val player = client.player
            val skin = player?.let { client.connection?.getPlayerInfo(it.uuid)?.skin }
            if (skin != null) PlayerFaceRenderer.draw(graphics, skin, layout.face.left, layout.face.top, layout.face.width)
            else drawFitted(graphics, Component.literal("?"), layout.face.left + 8, layout.face.top + 7, 12, theme.colors.textPrimary)

            val rankItem = when (view.rank) {
                "POKE_BALL" -> "poke_ball"
                "GREAT_BALL" -> "great_ball"
                "ULTRA_BALL" -> "ultra_ball"
                "MASTER_BALL" -> "master_ball"
                else -> null
            }?.let { BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", it)).orElse(null) }
            graphics.renderItem(ItemStack(rankItem ?: Items.NETHER_STAR), layout.ball.left, layout.ball.top)
            val rank = Component.translatable("screen.cobblemon_more_battle_content_league_challenge.home.rank." +
                view.rank.lowercase(Locale.ROOT))
            drawFitted(graphics, rank, layout.ball.right + 4, header.top + 13,
                layout.stats.left - layout.ball.right - 9, theme.colors.textPrimary)
            graphics.fill(layout.stats.left - 4, header.top + 4, layout.stats.left - 3, header.bottom - 4, theme.colors.borderBright)
            drawFitted(graphics, copy("header_badges", view.badges), layout.stats.left, header.top + 3,
                layout.stats.width, theme.colors.textPrimary)
            drawFitted(graphics, copy("header_cap", view.cap), layout.stats.left, header.top + 13,
                layout.stats.width, theme.colors.textPrimary)
            drawFitted(graphics, copy("header_bp", view.bp), layout.stats.left, header.top + 23,
                layout.stats.width, theme.colors.textPrimary)
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class RouteBackdrop(rect: LeagueDashboardRect) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.fill(x, y, x + width, y + height,
                theme.pixelDecorations?.titleBar ?: theme.colors.shell)
            graphics.fill(x + 20, y + 14, x + width - 20, y + 16, theme.colors.borderBright)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class RouteNode(x: Int, y: Int, width: Int, height: Int,
        private val entry: LeagueChallengeView, private val caption: Component,
        private val marker: String, private val selected: Boolean, enabled: Boolean,
        private val press: () -> Unit) : AbstractButton(x, y, width, height, Component.translatable(entry.nameKey)) {
        init { active = enabled }
        override fun onPress() = press()
        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val border = when {
                selected -> theme.colors.accentCaution
                isFocused || isHovered -> theme.colors.borderBright
                else -> theme.colors.border
            }
            graphics.fill(x + width / 2 - 16, y, x + width / 2 + 16, y + 21, border)
            graphics.fill(x + width / 2 - 14, y + 2, x + width / 2 + 14, y + 19, theme.colors.shell)
            val badge = badgeStack(entry.badgeId)
            if (badge != null) graphics.renderItem(badge, x + width / 2 - 8, y + 2)
            else graphics.drawCenteredString(font, marker, x + width / 2, y + 6,
                if (entry.status == "LOCKED") theme.colors.textDim else theme.colors.textPrimary)
            if (entry.status == "LOCKED") graphics.fill(x + width / 2 - 14, y + 2,
                x + width / 2 + 14, y + 19, 0x880A1A28.toInt())
            if (entry.status == "CLEARED") {
                graphics.fill(x + width / 2 + 7, y + 15, x + width / 2 + 13, y + 20, theme.colors.accentGood)
            } else if (entry.status == "AVAILABLE") {
                graphics.fill(x + width / 2 + 8, y + 15, x + width / 2 + 12, y + 19, theme.colors.accentPrimary)
            }
            val label = font.plainSubstrByWidth(caption.string, width)
            graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + 24,
                if (selected) theme.colors.accentCaution else theme.colors.textSecondary, false)
        }
    }

    private class ChallengeCard(rect: LeagueDashboardRect, private val view: LeagueView,
        private val selected: LeagueChallengeView?) : AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.fill(x + 2, y + 2, x + width - 2, y + 17, theme.colors.accentCaution)
            drawFitted(graphics, copy("dashboard_challenge"), x + 7, y + 5, width - 14, theme.colors.shell)
            val name = view.runNameKey?.let(Component::translatable)
                ?: selected?.let { Component.translatable(it.nameKey) } ?: copy("dashboard_no_challenge")
            val badge = badgeStack(selected?.badgeId)
            graphics.fill(x + 8, y + 24, x + 48, y + 66, theme.colors.borderBright)
            graphics.fill(x + 10, y + 26, x + 46, y + 64, theme.colors.shell)
            if (badge != null) {
                val pose = graphics.pose()
                pose.pushPose()
                pose.translate((x + 12).toDouble(), (y + 29).toDouble(), 0.0)
                pose.scale(2f, 2f, 1f)
                graphics.renderItem(badge, 0, 0)
                pose.popPose()
            } else drawFitted(graphics, Component.literal("L"), x + 24, y + 42, 10, theme.colors.textPrimary)
            val panelText = theme.surfaces.panelAltText ?: theme.colors.textPrimary
            drawFitted(graphics, name, x + 53, y + 26, width - 61, panelText)
            val status = selected?.let { copy("state." + it.status.lowercase(Locale.ROOT)) }
                ?: copy("dashboard_no_challenge")
            drawFitted(graphics, status, x + 53, y + 42, width - 61, panelText)
            graphics.fill(x + 8, y + 70, x + width - 8, y + 71, theme.colors.border)
            drawFitted(graphics, copy("dashboard_reward"), x + 8, y + 76, width - 16, panelText)
            selected?.let {
                drawFitted(graphics, copy("dashboard_cap_reward", it.unlockCap), x + 8, y + 90,
                    width - 16, panelText)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class StatusCard(rect: LeagueDashboardRect, private val view: LeagueView,
        private val selected: LeagueChallengeView?, private val pending: Boolean) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            graphics.fill(x + 2, y + 2, x + width - 2, y + 17, theme.colors.borderBright)
            drawFitted(graphics, copy("status"), x + 7, y + 5, width - 14, theme.colors.shell)
            val headline = when {
                view.errorKey != null -> copy("dashboard_attention")
                pending -> copy("waiting")
                view.pendingRewards -> copy("rewards_pending")
                view.runChallenge != null -> copy(if (view.awaitingNext) "next_ready" else "battle_active",
                    view.runNameKey?.let(Component::translatable) ?: copy("current_trainer"))
                view.champion -> copy("champion")
                selected != null -> copy("state." + selected.status.lowercase(Locale.ROOT))
                else -> copy("dashboard_no_challenge")
            }
            drawFitted(graphics, headline, x + 8, y + 23, width - 16,
                if (view.errorKey != null) theme.colors.accentDanger else theme.surfaces.panelAltText ?: theme.colors.border)
            val note = view.errorKey?.let(Component::translatable)
                ?: if (view.runChallenge != null) copy("dashboard_run_note") else copy("dashboard_team_note")
            val lines = font.split(note, (width - 16).coerceAtLeast(1))
                .take(((height - 45) / 10).coerceAtLeast(1))
            lines.forEachIndexed { index, line ->
                graphics.drawString(font, line, x + 8, y + 43 + index * 10,
                    theme.surfaces.panelAltText ?: theme.colors.border, false)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class FooterRule(rect: LeagueDashboardRect) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.fill(x, y, x + width, y + 1, theme.colors.borderBright)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    companion object {
        internal fun copy(key: String, vararg args: Any): Component =
            Component.translatable("screen.cobblemon_more_battle_content_league_challenge.live.$key", *args)
    }
}

private fun drawFitted(graphics: GuiGraphics, component: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width <= 0) return
    val font = Minecraft.getInstance().font
    val label = font.plainSubstrByWidth(component.string, width)
    graphics.drawString(font, label, x, y, color, false)
}

private fun badgeStack(id: String?): ItemStack? {
    val key = id?.let(ResourceLocation::tryParse) ?: return null
    return BuiltInRegistries.ITEM.getOptional(key).orElse(null)?.let(::ItemStack)
}
