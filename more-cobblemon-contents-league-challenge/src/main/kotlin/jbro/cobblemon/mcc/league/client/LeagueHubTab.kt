package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.league.network.LeagueAction
import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import jbro.cobblemon.mcc.league.network.LeagueView
import jbro.cobblemon.mcc.league.ui.LeagueHomePresentation
import jbro.cobblemon.mcc.league.ui.LeagueHubLayout
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
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
            MccHubKit.placeholder(host, bounds, leagueCopy("waiting"))
            return
        }
        val layout = LeagueHubLayout.calculate(bounds)
        val presentation = LeagueHomePresentation.from(view, state.selectedId)
        MccHubKit.strip(host, layout.summary,
            Component.empty().append(rankName(view)).append(Component.literal(" · ")).append(Component.translatable(view.nameKey)),
            Component.empty().append(leagueCopy("header_badges", view.badges)).append(Component.literal("  "))
                .append(leagueCopy("header_cap", view.cap)),
            ItemStack(rankItem(view) ?: Items.NETHER_STAR))
        addRoute(host, layout, presentation)
        val detail = MccHubKit.card(host, layout.detail, leagueCopy("dashboard_challenge"), MccHubKit.CardTone.FEATURE)
        host.add(ChallengeBody(detail, view, presentation.focused))
        val status = MccHubKit.card(host, layout.status, leagueCopy("status"))
        host.add(StatusBody(status, view, presentation.focused, state.pending))
        addActions(host, layout.footer, view)
    }

    private fun addRoute(host: MccHubContentHost, layout: LeagueHubLayout, presentation: LeagueHomePresentation) {
        host.add(RouteBackdrop(layout.route))
        val entries = presentation.gyms + presentation.finals
        val centers = layout.routeCenters(entries.size)
        val step = centers.zipWithNext { a, b -> b - a }.minOrNull() ?: 44
        val nodeWidth = (step - 2).coerceIn(28, 80)
        entries.forEachIndexed { index, entry ->
            val final = index == presentation.gyms.size
            val node = RouteNode(centers[index] - nodeWidth / 2, layout.route.y + 1, nodeWidth, 37, entry,
                if (final) leagueCopy("route_league") else Component.translatable(entry.nameKey),
                if (final) "L" else (index + 1).toString(), state.selectedId == entry.id, !state.pending) {
                state.select(entry.id)
                host.rebuild()
            }
            node.tooltip = Tooltip.create(leagueCopy("entry", leagueCopy("state." + entry.status.lowercase(Locale.ROOT)),
                entry.unlockCap))
            host.add(node)
        }
    }

    private fun addActions(host: MccHubContentHost, footer: UiRect, view: LeagueView) {
        val next = view.runChallenge != null
        val start = buildList {
            add(MccHubKit.Action(leagueCopy("refresh"), enabled = !state.pending) { LeagueHomeController.send(LeagueAction.REFRESH) })
            if (next) add(MccHubKit.Action(leagueCopy("forfeit"), UiButtonVariant.DANGER, state.canCancel) {
                val nonce = view.nonce
                MccHubKit.confirm(leagueCopy("forfeit_title"), leagueCopy("forfeit_body"), leagueCopy("forfeit"), leagueCopy("back")) {
                    if (state.view?.nonce == nonce) LeagueHomeController.send(LeagueAction.CANCEL)
                }
            })
        }
        val primary = MccHubKit.Action(leagueCopy(if (next) "next" else "challenge"), UiButtonVariant.PRIMARY,
            if (next) state.canNext else state.canStart, minWidth = 118) {
            LeagueHomeController.send(if (next) LeagueAction.NEXT else LeagueAction.START)
        }
        MccHubKit.footer(host, footer, start, listOf(primary))
    }

    private class RouteBackdrop(rect: UiRect) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
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
            val label = MccHubKit.fitted(caption, width)
            graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + 24,
                if (selected) theme.colors.accentCaution else theme.colors.textSecondary, false)
        }
    }

    /**
     * The focused challenge: its badge in a frame, name and state beside it, the unlock reward below. The frame
     * grows with the card and the whole block sits a little above the middle, so a tall card is not top-heavy.
     */
    private class ChallengeBody(private val body: UiRect, private val view: LeagueView,
        private val selected: LeagueChallengeView?) : AbstractWidget(body.x, body.y, body.width, body.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val text = MccHubKit.panelText(theme)
            val box = (body.height - REWARD_ROWS).coerceAtMost(body.width * 2 / 5).coerceIn(30, 96)
            val top = body.y + ((body.height - box - REWARD_ROWS) / 3).coerceAtLeast(0)
            graphics.fill(body.x, top, body.x + box, top + box, theme.colors.borderBright)
            graphics.fill(body.x + 2, top + 2, body.x + box - 2, top + box - 2, theme.colors.shell)
            val badge = badgeStack(selected?.badgeId)
            if (badge != null) {
                val scale = (((box - 6) / 16f) * 2).toInt().coerceIn(2, 10) / 2f
                val pose = graphics.pose()
                pose.pushPose()
                pose.translate(body.x + (box - 16 * scale) / 2.0, top + (box - 16 * scale) / 2.0, 0.0)
                pose.scale(scale, scale, 1f)
                graphics.renderItem(badge, 0, 0)
                pose.popPose()
            } else {
                drawLine(graphics, Component.literal("L"), body.x + box / 2 - 2, top + box / 2 - 4, 10, theme.colors.textPrimary)
            }
            val textLeft = body.x + box + 8
            val textWidth = body.right - textLeft
            val name = view.runNameKey?.let(Component::translatable)
                ?: selected?.let { Component.translatable(it.nameKey) } ?: leagueCopy("dashboard_no_challenge")
            val state = selected?.let { leagueCopy("state." + it.status.lowercase(Locale.ROOT)) } ?: leagueCopy("dashboard_no_challenge")
            val lineTop = top + (box - 22) / 2
            drawLine(graphics, name, textLeft, lineTop, textWidth, text)
            drawLine(graphics, state, textLeft, lineTop + 12, textWidth, text)
            val rule = top + box + 5
            graphics.fill(body.x, rule, body.right, rule + 1, theme.colors.border)
            drawLine(graphics, leagueCopy("dashboard_reward"), body.x, rule + 5, body.width, text)
            selected?.let { drawLine(graphics, leagueCopy("dashboard_cap_reward", it.unlockCap), body.x, rule + 17, body.width, text) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

        private companion object {
            /** Rows below the badge frame: a gap, a rule and two reward lines. */
            const val REWARD_ROWS = 31
        }
    }

    private class StatusBody(private val body: UiRect, private val view: LeagueView,
        private val selected: LeagueChallengeView?, private val pending: Boolean) :
        AbstractWidget(body.x, body.y, body.width, body.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
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
            val text = MccHubKit.panelText(theme)
            drawLine(graphics, headline, body.x, body.y, body.width, if (view.errorKey != null) theme.colors.accentDanger else text)
            val note = view.errorKey?.let(Component::translatable)
                ?: if (view.runChallenge != null) leagueCopy("dashboard_run_note") else leagueCopy("dashboard_team_note")
            font.split(note, body.width.coerceAtLeast(1)).take(((body.height - 14) / 10).coerceAtLeast(1))
                .forEachIndexed { index, line -> graphics.drawString(font, line, body.x, body.y + 14 + index * 10, text, false) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private companion object {
        fun rankName(view: LeagueView): Component =
            Component.translatable("screen.more_cobblemon_contents_league_challenge.home.rank." + view.rank.lowercase(Locale.ROOT))

        fun rankItem(view: LeagueView) = when (view.rank) {
            "POKE_BALL" -> "poke_ball"
            "GREAT_BALL" -> "great_ball"
            "ULTRA_BALL" -> "ultra_ball"
            "MASTER_BALL" -> "master_ball"
            else -> null
        }?.let { BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", it)).orElse(null) }
    }
}

internal fun leagueCopy(key: String, vararg args: Any): Component =
    Component.translatable("screen.more_cobblemon_contents_league_challenge.live.$key", *args)

private fun drawLine(graphics: GuiGraphics, component: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width <= 0) return
    graphics.drawString(Minecraft.getInstance().font, MccHubKit.fitted(component, width), x, y, color, false)
}

private fun badgeStack(id: String?): ItemStack? {
    val key = id?.let(ResourceLocation::tryParse) ?: return null
    return BuiltInRegistries.ITEM.getOptional(key).orElse(null)?.let(::ItemStack)
}
