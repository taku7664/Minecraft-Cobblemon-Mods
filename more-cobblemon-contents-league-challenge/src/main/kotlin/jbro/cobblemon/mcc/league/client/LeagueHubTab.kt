package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.league.network.LeagueAction
import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import jbro.cobblemon.mcc.league.network.LeagueView
import jbro.cobblemon.mcc.league.ui.LeagueDashboardRect
import jbro.cobblemon.mcc.league.ui.LeagueHomePresentation
import jbro.cobblemon.mcc.league.ui.LeagueHubLayout
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/** League inside the MCC hub. All progression and challenge facts come from the server snapshot. */
internal class LeagueHubTab : MccHubTabContent {
    private val state get() = LeagueHomeController.state

    override fun shown() {
        // A server-opened hub already carries a fresh session; otherwise the tab asks for one.
        if (!LeagueHomeController.takeOpenedSession()) MccHubTabs.requestContent(LeagueHomeController.CONTENT)
    }

    override fun hidden() = LeagueHomeController.dismiss()

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val view = state.view
        if (view == null) {
            host.add(Waiting(bounds))
            return
        }
        val layout = LeagueHubLayout.calculate(bounds.x, bounds.y, bounds.width, bounds.height)
        val presentation = LeagueHomePresentation.from(view, state.selectedId)
        host.add(Summary(layout.summary, view))
        host.add(RouteBackdrop(layout.route))
        val entries = presentation.gyms + presentation.finals
        val centers = layout.routeCenters(entries.size)
        val step = centers.zipWithNext { a, b -> b - a }.minOrNull() ?: 44
        val nodeWidth = (step - 2).coerceIn(28, 80)
        entries.forEachIndexed { index, entry ->
            val final = index == presentation.gyms.size
            val node = RouteNode(centers[index] - nodeWidth / 2, layout.route.top + 1, nodeWidth, 37, entry,
                if (final) leagueCopy("route_league") else Component.translatable(entry.nameKey),
                if (final) "L" else (index + 1).toString(), state.selectedId == entry.id, !state.pending) {
                state.select(entry.id)
                host.rebuild()
            }
            node.tooltip = Tooltip.create(leagueCopy("entry", leagueCopy("state." + entry.status.lowercase(Locale.ROOT)),
                entry.unlockCap))
            host.add(node)
        }
        host.add(CobblemonUiPanel.create(layout.detail.left, layout.detail.top, layout.detail.width, layout.detail.height,
            UiPanelSpec(tone = UiPanelTone.RAISED)))
        host.add(CobblemonUiPanel.create(layout.status.left, layout.status.top, layout.status.width, layout.status.height,
            UiPanelSpec(tone = UiPanelTone.RAISED)))
        host.add(ChallengeCard(layout.detail, view, presentation.focused))
        host.add(StatusCard(layout.status, view, presentation.focused, state.pending))
        addActions(host, layout.footer, view)
    }

    private fun addActions(host: MccHubContentHost, footer: LeagueDashboardRect, view: LeagueView) {
        host.add(FooterRule(footer))
        val y = footer.top + 3
        val primaryWidth = minOf(118, footer.width / 3)
        val primaryX = footer.right - primaryWidth
        val next = view.runChallenge != null
        val primary = CobblemonUiButton.create(primaryX, y, primaryWidth,
            UiButtonSpec(leagueCopy(if (next) "next" else "challenge"), variant = UiButtonVariant.PRIMARY,
                size = UiControlSize.MEDIUM, width = UiWidthPolicy.Fixed(primaryWidth))) {
            LeagueHomeController.send(if (next) LeagueAction.NEXT else LeagueAction.START)
        }
        primary.active = if (next) state.canNext else state.canStart
        host.add(primary)

        var x = footer.left
        fun action(key: String, enabled: Boolean, variant: UiButtonVariant, press: () -> Unit) {
            val button = host.add(CobblemonUiButton.create(x, y, primaryX - x - 3,
                UiButtonSpec(leagueCopy(key), variant = variant, size = UiControlSize.MEDIUM), press = press))
            button.active = enabled
            x += button.width + 4
        }
        action("refresh", !state.pending, UiButtonVariant.SECONDARY) { LeagueHomeController.send(LeagueAction.REFRESH) }
        if (next) action("forfeit", state.canCancel, UiButtonVariant.DANGER) {
            val client = Minecraft.getInstance()
            val parent = client.screen ?: return@action
            val nonce = view.nonce
            client.setScreen(CobblemonUiDialogScreen(parent,
                UiDialogSpec(leagueCopy("forfeit_title"), leagueCopy("forfeit_body"), leagueCopy("forfeit"), leagueCopy("back"),
                    UiOverlayTone.DANGER),
                confirm = { if (state.view?.nonce == nonce) LeagueHomeController.send(LeagueAction.CANCEL) },
                themeOverride = CobblemonUiThemePresets.snapshot(UiThemePreset.PIXEL_LEAGUE)))
        }
    }

    private class Waiting(private val bounds: UiRect) :
        AbstractWidget(bounds.x, bounds.y, bounds.width, bounds.height, leagueCopy("waiting")) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val text = leagueCopy("waiting")
            graphics.drawString(font, text, bounds.x + (bounds.width - font.width(text)) / 2,
                bounds.y + (bounds.height - font.lineHeight) / 2, theme.colors.textDim, false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
    }

    /** Rank, league name, badges and level cap. The hub header already shows BP. */
    private class Summary(private val rect: LeagueDashboardRect, private val view: LeagueView) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            graphics.fill(rect.left, rect.top, rect.right, rect.bottom, theme.pixelDecorations?.titleBar ?: theme.colors.panel)
            graphics.fill(rect.left, rect.bottom - 1, rect.right, rect.bottom, theme.colors.border)
            val rankItem = when (view.rank) {
                "POKE_BALL" -> "poke_ball"
                "GREAT_BALL" -> "great_ball"
                "ULTRA_BALL" -> "ultra_ball"
                "MASTER_BALL" -> "master_ball"
                else -> null
            }?.let { BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", it)).orElse(null) }
            graphics.renderItem(ItemStack(rankItem ?: Items.NETHER_STAR), rect.left + 3, rect.top + 2)
            val textY = rect.top + (rect.height - 1 - font.lineHeight) / 2 + 1
            val stats = Component.empty().append(leagueCopy("header_badges", view.badges))
                .append(Component.literal("  ")).append(leagueCopy("header_cap", view.cap))
            val statsWidth = font.width(stats)
            graphics.drawString(font, stats, rect.right - statsWidth - 6, textY, theme.colors.textPrimary, false)
            val rank = Component.translatable("screen.more_cobblemon_contents_league_challenge.home.rank." +
                view.rank.lowercase(Locale.ROOT))
            val nameRoom = rect.right - statsWidth - 12 - (rect.left + 23)
            val title = Component.empty().append(rank).append(Component.literal(" · ")).append(Component.translatable(view.nameKey))
            drawFitted(graphics, title, rect.left + 23, textY, nameRoom, theme.colors.textPrimary)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class RouteBackdrop(rect: LeagueDashboardRect) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            graphics.fill(x, y, x + width, y + height, theme.pixelDecorations?.titleBar ?: theme.colors.shell)
            graphics.fill(x + 18, y + 11, x + width - 18, y + 13, theme.colors.borderBright)
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
            val center = x + width / 2
            // Narrow routes shrink the badge frame so neighbouring frames do not touch.
            val half = if (width >= 32) 16 else 13
            val border = when {
                selected -> theme.colors.accentCaution
                isFocused || isHovered -> theme.colors.borderBright
                else -> theme.colors.border
            }
            graphics.fill(center - half, y, center + half, y + 21, border)
            graphics.fill(center - half + 2, y + 2, center + half - 2, y + 19, theme.colors.shell)
            val badge = badgeStack(entry.badgeId)
            if (badge != null) graphics.renderItem(badge, center - 8, y + 2)
            else graphics.drawCenteredString(font, marker, center, y + 6,
                if (entry.status == "LOCKED") theme.colors.textDim else theme.colors.textPrimary)
            if (entry.status == "LOCKED") graphics.fill(center - half + 2, y + 2, center + half - 2, y + 19, 0x880A1A28.toInt())
            if (entry.status == "CLEARED") {
                graphics.fill(center + half - 9, y + 15, center + half - 3, y + 20, theme.colors.accentGood)
            } else if (entry.status == "AVAILABLE") {
                graphics.fill(center + half - 8, y + 15, center + half - 4, y + 19, theme.colors.accentPrimary)
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
            drawFitted(graphics, leagueCopy("dashboard_challenge"), x + 7, y + 5, width - 14, theme.colors.shell)
            // A short card keeps every line by shrinking the badge frame from 42 to 34 rows.
            val box = if (height >= 100) 42 else 34
            val badgeScale = if (box >= 42) 2f else 1.5f
            val boxTop = y + 22
            graphics.fill(x + 8, boxTop, x + 8 + box, boxTop + box, theme.colors.borderBright)
            graphics.fill(x + 10, boxTop + 2, x + 6 + box, boxTop + box - 2, theme.colors.shell)
            val badge = badgeStack(selected?.badgeId)
            if (badge != null) {
                val pose = graphics.pose()
                pose.pushPose()
                pose.translate((x + 8 + (box - 16 * badgeScale) / 2).toDouble(), (boxTop + (box - 16 * badgeScale) / 2).toDouble(), 0.0)
                pose.scale(badgeScale, badgeScale, 1f)
                graphics.renderItem(badge, 0, 0)
                pose.popPose()
            } else drawFitted(graphics, Component.literal("L"), x + 8 + box / 2 - 2, boxTop + box / 2 - 4, 10, theme.colors.textPrimary)
            val panelText = theme.surfaces.panelAltText ?: theme.colors.textPrimary
            val textLeft = x + box + 14
            val name = view.runNameKey?.let(Component::translatable)
                ?: selected?.let { Component.translatable(it.nameKey) } ?: leagueCopy("dashboard_no_challenge")
            drawFitted(graphics, name, textLeft, boxTop + 2, x + width - 8 - textLeft, panelText)
            val status = selected?.let { leagueCopy("state." + it.status.lowercase(Locale.ROOT)) }
                ?: leagueCopy("dashboard_no_challenge")
            drawFitted(graphics, status, textLeft, boxTop + 14, x + width - 8 - textLeft, panelText)
            val rule = boxTop + box + 4
            graphics.fill(x + 8, rule, x + width - 8, rule + 1, theme.colors.border)
            drawFitted(graphics, leagueCopy("dashboard_reward"), x + 8, rule + 5, width - 16, panelText)
            selected?.let {
                drawFitted(graphics, leagueCopy("dashboard_cap_reward", it.unlockCap), x + 8, rule + 17, width - 16, panelText)
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
            drawFitted(graphics, leagueCopy("status"), x + 7, y + 5, width - 14, theme.colors.shell)
            val headline = when {
                view.errorKey != null -> leagueCopy("dashboard_attention")
                pending -> leagueCopy("waiting")
                view.pendingRewards -> leagueCopy("rewards_pending")
                view.runChallenge != null -> leagueCopy(if (view.awaitingNext) "next_ready" else "battle_active",
                    view.runNameKey?.let(Component::translatable) ?: leagueCopy("current_trainer"))
                view.champion -> leagueCopy("champion")
                selected != null -> leagueCopy("state." + selected.status.lowercase(Locale.ROOT))
                else -> leagueCopy("dashboard_no_challenge")
            }
            val body = theme.surfaces.panelAltText ?: theme.colors.border
            drawFitted(graphics, headline, x + 8, y + 23, width - 16,
                if (view.errorKey != null) theme.colors.accentDanger else body)
            val note = view.errorKey?.let(Component::translatable)
                ?: if (view.runChallenge != null) leagueCopy("dashboard_run_note") else leagueCopy("dashboard_team_note")
            font.split(note, (width - 16).coerceAtLeast(1))
                .take(((height - 40) / 10).coerceAtLeast(1))
                .forEachIndexed { index, line -> graphics.drawString(font, line, x + 8, y + 37 + index * 10, body, false) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class FooterRule(rect: LeagueDashboardRect) :
        AbstractWidget(rect.left, rect.top, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            graphics.fill(x, y, x + width, y + 1, CobblemonUiThemes.registry.snapshot().colors.borderBright)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }
}

internal fun leagueCopy(key: String, vararg args: Any): Component =
    Component.translatable("screen.more_cobblemon_contents_league_challenge.live.$key", *args)

private fun drawFitted(graphics: GuiGraphics, component: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width <= 0) return
    val font = Minecraft.getInstance().font
    graphics.drawString(font, font.plainSubstrByWidth(component.string, width), x, y, color, false)
}

private fun badgeStack(id: String?): ItemStack? {
    val key = id?.let(ResourceLocation::tryParse) ?: return null
    return BuiltInRegistries.ITEM.getOptional(key).orElse(null)?.let(::ItemStack)
}
