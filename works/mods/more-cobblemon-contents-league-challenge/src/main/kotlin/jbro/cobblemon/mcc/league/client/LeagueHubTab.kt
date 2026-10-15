package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.gui.TypeIcon
import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags
import jbro.cobblemon.mcc.client.hub.*
import jbro.cobblemon.mcc.league.network.*
import jbro.cobblemon.mcc.league.ui.*
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.CobblemonUiButton
import net.minecraft.client.Minecraft
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/** Facts stay server-authored; only selection and the finals branch are local. */
internal class LeagueHubTab : MccHubTabContent {
    private val state get() = LeagueHomeController.state
    private var routeStart = 0
    private var routeLeague: String? = null
    private var rewardList: Rewards? = null
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        rewardList?.mouseScrolled(mouseX, mouseY, 0.0, scrollY) == true
    override fun shown() {
        faces.clear()
        if (!LeagueHomeController.takeOpenedSession()) MccHubTabs.requestContent(LeagueHomeController.CONTENT)
    }
    override fun hidden() = LeagueHomeController.dismiss()
    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val view = state.view
        if (view == null) { MccHubKit.placeholder(host, bounds, leagueCopy("waiting")); return }
        val layout = LeagueHubLayout.calculate(bounds)
        val presentation = LeagueHomePresentation.from(view, state.selectedId, state.hard)
        val entries = presentation.gyms + presentation.finals
        if (routeLeague != view.leagueId) { routeStart = 0; routeLeague = view.leagueId }
        val lastStart = (entries.map { it.stage }.distinct().size - layout.routeCapacity).coerceAtLeast(0)
        routeStart = routeStart.coerceIn(0, lastStart)
        val rects = layout.nodeRects(entries, routeStart)
        val visible = entries.filter { it.id in rects }
        host.add(Canvas(layout, visible, rects))
        if (lastStart > 0) {
            listOf(false, true).forEach { next ->
                val r = UiRect(if (next) layout.route.right - 12 else layout.route.x,
                    layout.route.y + (layout.route.height - 18) / 2, 12, 18)
                host.add(CobblemonUiButton.create(r.x, r.y, r.width,
                    UiButtonSpec(Component.literal(if (next) ">" else "<"), variant = UiButtonVariant.SECONDARY,
                        size = UiControlSize.SMALL, width = UiWidthPolicy.Fixed(r.width)), press = {
                    routeStart = (routeStart + if (next) 1 else -1).coerceIn(0, lastStart); host.rebuild()
                }).also {
                    it.active = if (next) routeStart < lastStart else routeStart > 0
                    it.tooltip = Tooltip.create(leagueCopy(if (next) "route_next" else "route_previous"))
                })
            }
        }
        val regionWidth = 72
        val selectorWidth = regionWidth + 3 + 62
        MccHubKit.buttonRow(host, UiRect(layout.summary.x, layout.summary.y, regionWidth, layout.summary.height), listOf(
            MccHubKit.Action(Component.translatable(view.nameKey), enabled = !state.pending && view.runChallenge == null && view.regions.size > 1,
                tooltip = leagueCopy("select_region")) { LeagueHomeController.cycleRegion() }), UiControlSize.SMALL)
        listOf(false, true).forEachIndexed { index, hard ->
            host.add(CobblemonUiButton.create(layout.summary.x + regionWidth + 3 + index * 31, layout.summary.y, 31,
                UiButtonSpec(leagueCopy(if (hard) "difficulty_hard" else "difficulty_normal"),
                    variant = UiButtonVariant.SECONDARY, size = UiControlSize.SMALL, width = UiWidthPolicy.Fixed(31), selected = state.hard == hard), press = {
                    if (state.hard != hard && state.toggleHard()) host.rebuild()
                }).also {
                it.active = !state.pending && view.runChallenge == null && (!hard || view.hardUnlocked)
                it.tooltip = Tooltip.create(leagueCopy(if (hard && !view.hardUnlocked) "hard_locked" else "difficulty_tooltip"))
            })
        }
        host.add(Info(UiRect(layout.summary.x + selectorWidth + 3, layout.summary.y,
            (layout.summary.width - selectorWidth - 3).coerceAtLeast(1), layout.summary.height),
            Component.literal("${view.badges}/8 · Lv.${view.cap}")))
        visible.forEach { entry -> host.add(RouteNode(rects.getValue(entry.id), entry, entry.id == state.selectedId, !state.pending,
            if (entry.stage == 9) leagueCopy(if (view.leagueId.endsWith(":galar")) "cup_short" else "league_short") else Component.translatable(entry.nameKey)) {
            state.select(entry.id); host.rebuild()
        }) }
        host.add(Trainer(layout.detail, presentation.focused))
        fun column(r: UiRect) = UiRect(r.x + 5, r.y, r.width - 10, r.height)
        host.add(Gimmicks(column(layout.mechanics), presentation.focused))
        rewardList = host.add(Rewards(column(layout.status), view, presentation.focused))
        val primary = MccHubKit.Action(leagueCopy(if (view.runChallenge != null) "next" else "challenge"), UiButtonVariant.PRIMARY,
            if (view.runChallenge != null) state.canNext else state.canStart) {
            LeagueHomeController.send(if (view.runChallenge != null) LeagueAction.NEXT else LeagueAction.START)
        }
        val secondary = when {
            view.runChallenge != null -> MccHubKit.Action(leagueCopy("forfeit"), UiButtonVariant.DANGER, state.canCancel) {
                MccHubKit.confirm(leagueCopy("forfeit_title"), leagueCopy("forfeit_body"), leagueCopy("forfeit"), leagueCopy("back")) {
                    LeagueHomeController.send(LeagueAction.CANCEL)
                }
            }
            presentation.focused?.stage == 9 && state.finalBranches.size > 1 -> {
                val branch = state.finalBranches.firstOrNull { it.id == state.finalChoiceId }
                MccHubKit.Action(leagueCopy("cup_branch", branch?.let { Component.translatable(it.nameKey) } ?: Component.empty()),
                    enabled = !state.pending, tooltip = leagueCopy("cup_branch_hint")) {
                    if (state.cycleFinalBranch()) host.rebuild()
                }
            }
            else -> MccHubKit.Action(leagueCopy("refresh"), enabled = !state.pending) { LeagueHomeController.send(LeagueAction.REFRESH) }
        }
        val primaryWidth = (bounds.width * 2 / 5).coerceIn(84, 120)
        val secondaryWidth = if (presentation.focused?.stage == 9 && state.finalBranches.size > 1)
            bounds.width - primaryWidth - 3 else minOf(64, bounds.width / 3)
        MccHubKit.buttonRow(host, UiRect(layout.footer.x, layout.footer.y, secondaryWidth, layout.footer.height), listOf(secondary), UiControlSize.SMALL)
        MccHubKit.buttonRow(host, UiRect(layout.footer.right - primaryWidth, layout.footer.y, primaryWidth, layout.footer.height), listOf(primary), UiControlSize.SMALL)
    }

    private class Canvas(val layout: LeagueHubLayout, val entries: List<LeagueChallengeView>, val rects: Map<String, UiRect>) :
        AbstractWidget(layout.panel.x, layout.panel.y, layout.panel.width, layout.panel.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            UiSurfaceRenderer.draw(g, x, y, width, height, theme.surfaces.panel)
            val border = theme.colors.borderBright
            g.enableScissor(x + 2, y + 2, x + width - 2, y + height - 2)
            g.fill(layout.route.x, layout.body.y - 3, layout.route.right, layout.body.y - 2, border)
            listOf(layout.mechanics.x, layout.status.x).forEach {
                g.fill(it, layout.body.y + 1, it + 1, layout.body.bottom - 1, border)
            }
            val stages = entries.groupBy { it.stage }
            stages.keys.sorted().zipWithNext().forEach { (a, b) ->
                stages.getValue(a).forEach { from -> stages.getValue(b).forEach { to ->
                    val r = rects.getValue(from.id); val s = rects.getValue(to.id)
                    val middle = (r.right + s.x) / 2; val sy = r.y + r.height / 2; val ey = s.y + s.height / 2
                    g.fill(r.right, sy, middle + 1, sy + 1, border)
                    g.fill(middle, minOf(sy, ey), middle + 1, maxOf(sy, ey) + 1, border)
                    g.fill(middle, ey, s.x, ey + 1, border)
                } }
            }
            g.disableScissor()
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class RouteNode(r: UiRect, val entry: LeagueChallengeView, val selected: Boolean, enabled: Boolean,
        val caption: Component, val press: () -> Unit) :
        AbstractButton(r.x, r.y, r.width, r.height, Component.translatable(entry.nameKey)) {
        init {
            active = enabled
            tooltip = Tooltip.create(Component.empty().append(message).append("\n").append(leagueCopy("state." + entry.status.lowercase(Locale.ROOT))))
        }
        override fun onPress() = press()
        override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val style = theme.style(UiButtonVariant.SECONDARY, when {
                selected -> UiWidgetState.SELECTED; isHovered || isFocused -> UiWidgetState.HOVER; else -> UiWidgetState.NORMAL
            })
            g.enableScissor(x, y, x + width, y + height)
            val border = if (entry.status == "CLEARED") theme.colors.accentGood else theme.colors.borderBright
            UiSurfaceRenderer.draw(g, x, y, width, height, UiSurfaceStyle(UiShape.Rectangle,
                UiFill.Solid(if (entry.status == "LOCKED") theme.colors.panelAlt else theme.colors.panel), UiBorder.Solid(border)))
            val labels = height >= 24
            val size = minOf(12, height - if (labels) 12 else 4, width - 6).coerceAtLeast(4)
            val left = x + (width - size) / 2
            val top = y + 2
            val face = faceTexture(entry.skin)
            if (face != null) PlayerFaceRenderer.draw(g, face, left, top, size)
            else badgeStack(entry.badgeId)?.let { scaledItem(g, it, left, top, size) }
            if (entry.status == "LOCKED") g.fill(left, top, left + size, top + size, 0x66000000)
            if (labels) {
                g.pose().pushPose(); g.pose().translate((x + 2).toFloat(), (y + 2).toFloat(), 0f); g.pose().scale(.65f, .65f, 1f)
                g.drawString(Minecraft.getInstance().font, entry.stage.toString(), 0, 0, MccHubKit.panelText(theme), false); g.pose().popPose()
                val markerX = x + width - 7
                if (entry.status == "CLEARED") g.drawString(Minecraft.getInstance().font, "V", markerX, y + 2, theme.colors.accentGood, false)
                else if (entry.status == "LOCKED") {
                    g.fill(markerX + 1, y + 3, markerX + 5, y + 7, border)
                    g.fill(markerX + 2, y + 4, markerX + 4, y + 6, theme.colors.panelAlt)
                    g.fill(markerX, y + 6, markerX + 6, y + 10, border)
                }
                centeredLine(g, caption, x + 1, y + height - 10, width - 2, MccHubKit.panelText(theme))
            }
            if (selected || isFocused) UiSurfaceRenderer.drawSelection(g, x, y, width, height, style.surface.shape,
                theme.style(UiButtonVariant.SECONDARY, UiWidgetState.SELECTED).selectionIndicator)
            g.disableScissor()
        }
    }

    private class Info(r: UiRect, val text: Component) : AbstractWidget(r.x, r.y, r.width, r.height, text) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) =
            line(g, text, x, y + (height - 9) / 2, width, CobblemonUiThemes.registry.snapshot().colors.textPrimary)
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class Trainer(r: UiRect, val selected: LeagueChallengeView?) : AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val s = selected ?: return
            val theme = CobblemonUiThemes.registry.snapshot(); val color = MccHubKit.panelText(theme)
            g.enableScissor(x, y, x + width, y + height)
            val size = ((height - 37) / 8 * 8).coerceIn(8, 48).coerceAtMost((width - 4).coerceAtLeast(8))
            val faceX = x + (width - size) / 2; val top = y
            UiSurfaceRenderer.draw(g, faceX - 1, top, size + 2, size + 1,
                UiSurfaceStyle(UiShape.Rectangle, UiFill.Solid(theme.colors.panelAlt), UiBorder.Solid(theme.colors.borderBright)))
            faceTexture(s.skin)?.let { PlayerFaceRenderer.draw(g, it, faceX, top, size) }
                ?: badgeStack(s.badgeId)?.let { scaledItem(g, it, faceX, top, size) }
            val font = Minecraft.getInstance().font; val type = s.type?.let(ElementalTypes::get)
            val nameScale = 1.5f
            val name = MccHubKit.fitted(Component.translatable(s.nameKey).withStyle(ChatFormatting.BOLD),
                ((width - if (type == null) 4 else 16) / nameScale).toInt().coerceAtLeast(1))
            val textWidth = (font.width(name) * nameScale).toInt()
            val nameWidth = textWidth + if (type == null) 0 else 12
            val nameX = x + (width - nameWidth) / 2
            g.pose().pushPose(); g.pose().translate(nameX.toFloat(), (top + size + 3).toFloat(), 0f); g.pose().scale(nameScale, nameScale, 1f)
            g.drawString(font, name, 0, 0, color, false); g.pose().popPose()
            type?.let { TypeIcon(x = nameX + textWidth + 3, y = top + size + 5, type = it, small = true).render(g) }
            centeredLine(g, leagueCopy("challenge_cap", s.battleCap), x + 3, top + size + 18, width - 6, color)
            if (top + size + 37 <= y + height)
                centeredLine(g, leagueCopy("format." + s.format.lowercase(Locale.ROOT)), x + 3, top + size + 28, width - 6, color)
            tooltip = Tooltip.create(Component.empty().append(Component.translatable(s.nameKey)).append("\n").append(leagueCopy("challenge_cap", s.battleCap)))
            g.disableScissor()
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class Gimmicks(r: UiRect, val selected: LeagueChallengeView?) : AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot(); val color = MccHubKit.panelText(theme)
            val flags = selected?.mechanicFlags ?: 0
            tooltip = Tooltip.create(leagueCopy("mechanics"))
            g.enableScissor(x, y, x + width, y + height)
            line(g, leagueCopy(if (width < 70) "mechanics_short" else "mechanics"), x, y + 1, width, color)
            val rowHeight = ((height - 14) / 5).coerceIn(9, 15)
            listOf("mega" to BattleMechanicFlags.MEGA, "z_move" to BattleMechanicFlags.Z_MOVE,
                "dynamax" to BattleMechanicFlags.DYNAMAX, "gigantamax" to BattleMechanicFlags.DYNAMAX,
                "tera" to BattleMechanicFlags.TERA).forEachIndexed { index, (name, bit) ->
                val top = y + 14 + index * rowHeight; val yes = flags and bit != 0
                UiSurfaceRenderer.draw(g, x, top, 8, 9, UiSurfaceStyle(UiShape.Rectangle,
                    UiFill.Solid(theme.colors.panel), UiBorder.Solid(if (yes) theme.colors.accentGood else theme.colors.borderBright)))
                g.drawString(Minecraft.getInstance().font, if (yes) "V" else "X", x + 1, top,
                    if (yes) theme.colors.accentGood else color, false)
                line(g, leagueCopy("mechanic.$name"), x + 11, top, width - 11, color)
            }
            g.disableScissor()
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class Rewards(r: UiRect, val view: LeagueView, val selected: LeagueChallengeView?) : AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
        private var scroll = 0
        private var maxScroll = 0
        init { active = false }
        override fun mouseScrolled(mx: Double, my: Double, horizontal: Double, vertical: Double): Boolean {
            if (mx < x || mx >= x + width || my < y || my >= y + height || maxScroll == 0) return false
            scroll = (scroll - vertical.toInt()).coerceIn(0, maxScroll); return true
        }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot(); val color = MccHubKit.panelText(theme)
            line(g, leagueCopy("clear_rewards"), x, y + 1, width, color)
            val rows = buildList<Component> {
                view.errorKey?.let { add(Component.translatable(it)) }
                if (!view.leagueUnlocked) add(leagueCopy("league_locked"))
                if (view.pendingRewards) add(leagueCopy("rewards_pending"))
                selected?.let { s ->
                    if (s.status == "CLEARED") add(leagueCopy("first_reward_claimed"))
                    else {
                        if (s.badgeId != null) add(leagueCopy("reward_badge"))
                        if (s.bp > 0) add(leagueCopy("reward_bp", s.bp))
                        if (s.unlockCap > view.cap) add(leagueCopy("reward_cap", s.unlockCap))
                        s.unlockKeys.forEach { key ->
                            add(if (key.startsWith("trainer.")) leagueCopy("reward_unlock", Component.translatable(key)) else Component.translatable(key))
                        }
                    }
                }
            }
            val font = Minecraft.getInstance().font
            val lines = rows.flatMap { font.split(it, (width - 3).coerceAtLeast(1)) }
            val maxRows = ((height - 14) / 10).coerceAtLeast(1)
            maxScroll = (lines.size - maxRows).coerceAtLeast(0); scroll = scroll.coerceIn(0, maxScroll)
            g.enableScissor(x, y + 14, x + width, y + height)
            lines.drop(scroll).take(maxRows).forEachIndexed { index, value -> g.drawString(font, value, x, y + 14 + index * 10, color, false) }
            if (maxScroll > 0) g.fill(x + width - 1, y + scroll * (height - 4) / maxScroll, x + width, y + scroll * (height - 4) / maxScroll + 4, theme.colors.borderBright)
            g.disableScissor()
            tooltip = Tooltip.create(rows.fold(Component.empty()) { text, row -> text.append(row).append("\n") })
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }
}
internal fun rankName(view: LeagueView): Component = rankName(view.rank)
internal fun rankName(rank: String): Component = Component.translatable("screen.more_cobblemon_contents_league_challenge.home.rank." + rank.lowercase(Locale.ROOT))
internal fun leagueCopy(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents_league_challenge.live.$key", *args)
internal fun rankStack(rank: String): ItemStack = when (rank) {
    "POKE_BALL" -> "poke_ball"; "GREAT_BALL" -> "great_ball"; "ULTRA_BALL" -> "ultra_ball"; "MASTER_BALL" -> "master_ball"; else -> null
}?.let { BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", it)).orElse(null) }?.let(::ItemStack) ?: ItemStack(Items.NETHER_STAR)
private val faces = HashMap<String, ResourceLocation?>()
private fun faceTexture(skin: String?): ResourceLocation? = skin?.let { faces.getOrPut(it) {
    ResourceLocation.tryParse(it)?.takeIf { resource -> Minecraft.getInstance().resourceManager.getResource(resource).isPresent }
} }
private fun badgeStack(id: String?): ItemStack? = id?.let(ResourceLocation::tryParse)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.let(::ItemStack)
private fun line(g: GuiGraphics, text: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width > 0) g.drawString(Minecraft.getInstance().font, MccHubKit.fitted(text, width), x, y, color, false)
}
private fun centeredLine(g: GuiGraphics, text: Component, x: Int, y: Int, width: Int, color: Int) {
    if (width <= 0) return
    val font = Minecraft.getInstance().font
    val fitted = MccHubKit.fitted(text, width)
    g.drawString(font, fitted, x + (width - font.width(fitted)) / 2, y, color, false)
}
private fun scaledItem(g: GuiGraphics, item: ItemStack, x: Int, y: Int, size: Int) {
    g.pose().pushPose(); g.pose().translate(x.toFloat(), y.toFloat(), 0f); g.pose().scale(size / 16f, size / 16f, 1f)
    g.renderItem(item, 0, 0); g.pose().popPose()
}
