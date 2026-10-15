package jbro.cobblemon.mcc.league.client

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.gui.TypeIcon
import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags
import jbro.cobblemon.mcc.client.hub.*
import jbro.cobblemon.mcc.league.network.*
import jbro.cobblemon.mcc.league.ui.*
import jbro.cobblemon.uikit.*
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiTabBar
import jbro.cobblemon.uikit.client.UiPixelBox
import jbro.cobblemon.uikit.client.UiTextRenderer
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

/** Facts stay server-authored; only selection, the shown route and the finals branch are local. */
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
        if (view == null) { MccHubKit.placeholder(host, bounds, leagueCopy("waiting"), onCard = false); return }
        val layout = LeagueHubLayout.calculate(bounds)
        val presentation = LeagueHomePresentation.from(view, state.selectedId, state.hard)
        val running = view.runChallenge != null
        addRegions(host, layout, view, running)
        // Once the finals are next or running, their trainers take the route's place, the one faced now in focus.
        val finalsView = state.finalsView
        val entries = if (finalsView) state.challenges else presentation.gyms + presentation.finals
        val focused = if (!finalsView) presentation.focused
            else if (running) entries.firstOrNull { it.status == "AVAILABLE" } ?: presentation.focused
            else entries.firstOrNull { it.id == state.selectedId }
        addRoute(host, layout, view, entries, focused, finalsView)
        host.add(Challenger(MccHubKit.card(host, layout.challenger, leagueCopy("challenger"), MccHubKit.CardTone.FEATURE), focused))
        host.add(Gimmicks(MccHubKit.card(host, layout.mechanics,
            leagueCopy(if (layout.mechanics.width < 90) "mechanics_short" else "mechanics")), focused))
        rewardList = host.add(Rewards(MccHubKit.card(host, layout.rewards, leagueCopy("clear_rewards"), MccHubKit.CardTone.GOOD), view, focused))
        addFooter(host, layout, view, focused, running)
    }

    /** Region tabs, a locked region shown with its lock; the badges and cap of the shown League at the row's end. */
    private fun addRegions(host: MccHubContentHost, layout: LeagueHubLayout, view: LeagueView, running: Boolean) {
        val regions = view.regions.ifEmpty { listOf(LeagueRegionView(view.leagueId, view.nameKey, true)) }
        val items = regions.map { region ->
            CobblemonUiTabBar.Item(region.id, Component.translatable(region.nameKey), locked = !region.unlocked,
                enabled = !state.pending && (!running || region.id == view.leagueId),
                tooltip = if (!region.unlocked) leagueCopy("league_locked") else null)
        }
        CobblemonUiTabBar.create({ host.add(it) }, layout.tabs, layout.page.bottom, items, view.leagueId) { id ->
            LeagueHomeController.selectRegion(id)
        }
        val tabsRight = layout.tabs.x + CobblemonUiTabBar.widths(items).sum() + (items.size - 1) * 2 + 6
        host.add(Status(UiRect(tabsRight, layout.tabs.y, (layout.tabs.right - tabsRight).coerceAtLeast(1), layout.tabs.height),
            leagueCopy("status_line", view.badges, view.cap)))
    }

    private fun addRoute(host: MccHubContentHost, layout: LeagueHubLayout, view: LeagueView,
        entries: List<LeagueChallengeView>, focused: LeagueChallengeView?, finalsView: Boolean) {
        val shown = view.leagueId + state.hard + finalsView
        if (routeLeague != shown) { routeStart = 0; routeLeague = shown }
        val stages = entries.map { it.stage }.distinct().size
        val paged = stages > layout.routeCapacity
        val area = layout.routeArea(paged)
        val lastStart = if (paged) (stages - layout.pagedCapacity()).coerceAtLeast(0) else 0
        routeStart = routeStart.coerceIn(0, lastStart)
        val rects = layout.nodeRects(entries, routeStart, area)
        val visible = entries.filter { it.id in rects }
        host.add(Connectors(layout.page, visible, rects))
        visible.forEach { entry ->
            val caption = if (entry.finalStage && !finalsView) leagueCopy(if (view.leagueId.endsWith(":galar")) "cup_short" else "league_short")
                else Component.translatable(entry.nameKey)
            host.add(RouteNode(rects.getValue(entry.id), entry, entry.id == focused?.id, !state.pending && view.runChallenge == null, caption) {
                state.select(entry.id); host.rebuild()
            })
        }
        if (!paged) return
        listOf(false, true).forEach { next ->
            val r = UiRect(if (next) layout.route.right - LeagueHubLayout.ARROW_WIDTH else layout.route.x,
                layout.route.y + (layout.route.height - 18) / 2, LeagueHubLayout.ARROW_WIDTH, 18)
            val button = CobblemonUiButton.create(r.x, r.y, r.width, UiButtonSpec(Component.literal(if (next) ">" else "<"),
                size = UiControlSize.SMALL, width = UiWidthPolicy.Fixed(r.width))) {
                routeStart = (routeStart + if (next) 1 else -1).coerceIn(0, lastStart); host.rebuild()
            }
            button.active = if (next) routeStart < lastStart else routeStart > 0
            button.setTooltip(Tooltip.create(leagueCopy(if (next) "route_next" else "route_previous")))
            host.add(button)
        }
    }

    private fun addFooter(host: MccHubContentHost, layout: LeagueHubLayout, view: LeagueView,
        focused: LeagueChallengeView?, running: Boolean) {
        val start = buildList {
            if (running) add(MccHubKit.Action(leagueCopy("forfeit"), UiButtonVariant.DANGER, state.canCancel) {
                MccHubKit.confirm(leagueCopy("forfeit_title"), leagueCopy("forfeit_body"), leagueCopy("forfeit"), leagueCopy("back")) {
                    LeagueHomeController.send(LeagueAction.CANCEL)
                }
            }) else add(MccHubKit.Action(leagueCopy("refresh"), enabled = !state.pending) { LeagueHomeController.send(LeagueAction.REFRESH) })
            if (!running && focused?.finalStage == true && state.finalBranches.size > 1) {
                val branch = state.finalBranches.firstOrNull { it.id == state.finalChoiceId }
                add(MccHubKit.Action(leagueCopy("cup_branch", branch?.let { Component.translatable(it.nameKey) } ?: Component.empty()),
                    enabled = !state.pending, tooltip = leagueCopy("cup_branch_hint")) { if (state.cycleFinalBranch()) host.rebuild() })
            }
        }
        val difficulty = listOf(false, true).map { hard ->
            val locked = hard && !view.hardUnlocked
            MccHubKit.Action(leagueCopy(if (hard) "difficulty_hard" else "difficulty_normal"),
                enabled = !state.pending && !running && !locked,
                tooltip = leagueCopy(if (locked) "hard_locked" else "difficulty_tooltip"),
                selected = state.hard == hard) { if (state.hard != hard && state.toggleHard()) host.rebuild() }
        }
        val primary = MccHubKit.Action(leagueCopy(if (running) "next" else "challenge"), UiButtonVariant.PRIMARY,
            if (running) state.canNext else state.canStart, minWidth = 64) {
            LeagueHomeController.send(if (running) LeagueAction.NEXT else LeagueAction.START)
        }
        MccHubKit.footer(host, layout.footer, start, difficulty + primary)
    }

    /** "Badges 3/8 · Level cap 30", right-aligned beside the region tabs. */
    private class Status(r: UiRect, val text: Component) : AbstractWidget(r.x, r.y, r.width, r.height, text) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val font = Minecraft.getInstance().font
            val line = MccHubKit.fitted(text, width)
            g.drawString(font, line, x + width - font.width(line), UiTextRenderer.centeredY(font, line, y, height),
                CobblemonUiThemes.registry.snapshot().colors.textPrimary, false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** The lines from each stage to the next, behind the nodes; a line out of a cleared node is lit. */
    private class Connectors(page: UiRect, val entries: List<LeagueChallengeView>, val rects: Map<String, UiRect>) :
        AbstractWidget(page.x, page.y, page.width, page.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val stages = entries.groupBy { it.stage }
            stages.keys.sorted().zipWithNext().forEach { (a, b) ->
                stages.getValue(a).forEach { from -> stages.getValue(b).forEach { to ->
                    val color = if (from.status == "CLEARED") theme.colors.accentGood else theme.colors.shell
                    val r = box(rects.getValue(from.id)); val s = box(rects.getValue(to.id))
                    val middle = (r.right + s.x) / 2
                    val sy = r.y + r.height / 2; val ey = s.y + s.height / 2
                    g.fill(r.right, sy, middle + 1, sy + 1, color)
                    g.fill(middle, minOf(sy, ey), middle + 1, maxOf(sy, ey) + 1, color)
                    g.fill(middle, ey, s.x, ey + 1, color)
                } }
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** One stage: the trainer's face in a square box, the name under it, a check once beaten and a lock before. */
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
            val font = Minecraft.getInstance().font
            val box = box(UiRect(x, y, width, height))
            val locked = entry.status == "LOCKED"
            val cleared = entry.status == "CLEARED"
            // Selection: a square two-pixel ring outside the box, like the box itself square.
            if (selected) UiPixelBox.ring(g, theme, box.x - 2, box.y - 2, box.width + 4, box.height + 4, theme.colors.accentCaution, 2)
            else if (active && (isHovered || isFocused)) UiPixelBox.ring(g, theme, box.x - 1, box.y - 1, box.width + 2, box.height + 2, theme.colors.textDim, 1)
            val edge = when {
                cleared -> theme.colors.accentGood
                locked -> theme.colors.shell
                else -> theme.colors.borderBright
            }
            UiPixelBox.draw(g, theme, box.x, box.y, box.width, box.height, edge, theme.pixelDecorations?.titleBarShade ?: theme.colors.border)
            val face = faceTexture(entry.skin)
            if (face != null) PlayerFaceRenderer.draw(g, face, box.x + 1, box.y + 1, box.width - 2)
            else badgeStack(entry.badgeId)?.let { scaledItem(g, it, box.x + 1, box.y + 1, box.width - 2) }
            if (locked) {
                g.fill(box.x + 1, box.y + 1, box.right - 1, box.bottom - 1, 0x99000000.toInt())
                CobblemonUiTabBar.drawLock(g, box.x + (box.width - 7) / 2, box.y + (box.height - 8) / 2, theme.colors.textDim)
            }
            if (cleared) drawCheck(g, box.right - 6, box.y - 2, theme.colors.accentGood, theme.colors.border)
            val color = when {
                selected -> theme.colors.textPrimary
                locked -> theme.colors.textDim
                else -> theme.colors.textSecondary
            }
            // A full-width node lends its name two pixels a side; a narrowed one keeps it inside, clear of its neighbours.
            val spill = if (width >= LeagueHubLayout.NODE_WIDTH) 2 else 0
            if (height > box.height) centeredLine(g, caption, x - spill, box.bottom + 2, width + spill * 2, color)
        }
    }

    /** The focused challenge: face, name and type, the level the battle runs at and its format. */
    private class Challenger(r: UiRect, val selected: LeagueChallengeView?) : AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val s = selected ?: return
            val theme = CobblemonUiThemes.registry.snapshot(); val color = MccHubKit.panelText(theme)
            val font = Minecraft.getInstance().font
            g.enableScissor(x, y, x + width, y + height)
            val size = minOf(height, 34, width / 3).coerceAtLeast(10)
            val top = y + (height - size) / 2
            UiPixelBox.draw(g, theme, x, top, size, size, theme.colors.border, theme.pixelDecorations?.titleBar ?: theme.colors.shell)
            faceTexture(s.skin)?.let { PlayerFaceRenderer.draw(g, it, x + 1, top + 1, size - 2) }
                ?: badgeStack(s.badgeId)?.let { scaledItem(g, it, x + 1, top + 1, size - 2) }
            val left = x + size + 5
            val room = x + width - left
            val type = s.type?.let(ElementalTypes::get)
            // Plain weight: the pixel font draws bold as a second copy one pixel over, which reads as a doubled name.
            val name = MccHubKit.fitted(Component.translatable(s.nameKey), room - if (type == null) 0 else 12)
            val lines = 4
            var lineY = y + (height - lines * 10) / 2 + 1
            g.drawString(font, name, left, lineY, color, false)
            type?.let { TypeIcon(x = left + font.width(name) + 2, y = lineY - 1, type = it, small = true).render(g) }
            lineY += 11
            line(g, leagueCopy("challenge_cap", s.battleCap), left, lineY, room, color); lineY += 10
            line(g, leagueCopy("format." + s.format.lowercase(Locale.ROOT)), left, lineY, room, color); lineY += 10
            val stateColor = when (s.status) {
                "CLEARED" -> theme.colors.accentGood
                "LOCKED" -> theme.colors.accentDanger
                else -> theme.colors.accentPrimary
            }
            line(g, leagueCopy("state." + s.status.lowercase(Locale.ROOT)), left, lineY, room, stateColor)
            tooltip = Tooltip.create(Component.empty().append(Component.translatable(s.nameKey)).append("\n").append(leagueCopy("challenge_cap", s.battleCap))
                .append("\n").append(leagueCopy("format." + s.format.lowercase(Locale.ROOT))))
            g.disableScissor()
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** Every battle gimmick on its own line, a V box for the ones this battle allows and an X box for the rest. */
    private class Gimmicks(r: UiRect, val selected: LeagueChallengeView?) : AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
            val theme = CobblemonUiThemes.registry.snapshot(); val color = MccHubKit.panelText(theme)
            val font = Minecraft.getInstance().font
            val flags = selected?.mechanicFlags ?: 0
            g.enableScissor(x, y, x + width, y + height)
            val rowHeight = (height / MECHANICS.size).coerceIn(9, 12)
            val top = y + (height - rowHeight * MECHANICS.size) / 2
            MECHANICS.forEachIndexed { index, (name, bit) ->
                val rowY = top + index * rowHeight
                val yes = flags and bit != 0
                val mark = if (yes) theme.colors.accentGood else theme.colors.accentDanger
                // A 7x7 tile in the mark's colour with a pale V or X in pixels, centred on the row so tiles stay apart.
                val top = rowY + (rowHeight - 7) / 2
                UiPixelBox.draw(g, theme, x, top, 7, 7, mark, mark)
                drawMark(g, x + 1, top + 1, yes, theme.colors.panel)
                val label = MccHubKit.fitted(leagueCopy("mechanic.$name"), width - 11)
                g.drawString(font, label, x + 11, UiTextRenderer.centeredY(font, label, top, 7), color, false)
            }
            g.disableScissor()
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** What beating the focused challenge gives, and any notice the League has for the player first. */
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
            val notices = buildList<Component> {
                view.errorKey?.let { add(Component.translatable(it)) }
                if (!view.leagueUnlocked) add(leagueCopy("league_locked"))
                if (view.pendingRewards) add(leagueCopy("rewards_pending"))
            }
            val rows = buildList<Component> {
                selected?.let { s ->
                    if (s.status == "CLEARED") add(leagueCopy("first_reward_claimed"))
                    else {
                        if (s.badgeId != null) add(leagueCopy("reward_badge"))
                        if (s.bp > 0) add(leagueCopy("reward_bp", s.bp))
                        if (s.unlockCap > view.cap) add(leagueCopy("reward_cap", s.unlockCap))
                        // A trainer already open (the other way of a passed stage opened them) is no reward.
                        val open = (view.challenges + view.hardChallenges).filter { it.status != "LOCKED" }.map { it.nameKey }.toSet()
                        s.unlockKeys.filter { it !in open }.forEach { key ->
                            add(if (key.startsWith("trainer.")) leagueCopy("reward_unlock", Component.translatable(key)) else Component.translatable(key))
                        }
                    }
                }
            }
            val font = Minecraft.getInstance().font
            val lines = notices.flatMap { font.split(it, (width - 3).coerceAtLeast(1)).map { line -> line to theme.colors.accentDanger } } +
                rows.flatMap { row -> font.split(Component.literal("· ").append(row), (width - 3).coerceAtLeast(1)).map { line -> line to color } }
            val maxRows = (height / 10).coerceAtLeast(1)
            maxScroll = (lines.size - maxRows).coerceAtLeast(0); scroll = scroll.coerceIn(0, maxScroll)
            g.enableScissor(x, y, x + width, y + height)
            lines.drop(scroll).take(maxRows).forEachIndexed { index, (value, tint) -> g.drawString(font, value, x, y + 1 + index * 10, tint, false) }
            if (maxScroll > 0) {
                val thumb = y + scroll * (height - 6) / maxScroll
                g.fill(x + width - 2, thumb, x + width, thumb + 6, theme.colors.borderBright)
            }
            g.disableScissor()
            tooltip = Tooltip.create((notices + rows).fold(Component.empty()) { text, row -> text.append(row).append("\n") })
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }
}

private val MECHANICS = listOf("mega" to BattleMechanicFlags.MEGA, "z_move" to BattleMechanicFlags.Z_MOVE,
    "dynamax" to BattleMechanicFlags.DYNAMAX, "gigantamax" to BattleMechanicFlags.DYNAMAX, "tera" to BattleMechanicFlags.TERA)

/** The node's icon box: square, centred over its name line. */
private fun box(node: UiRect): UiRect =
    UiRect(node.x + (node.width - LeagueHubLayout.BOX) / 2, node.y, LeagueHubLayout.BOX, LeagueHubLayout.BOX)

/** A 5x5 V (allowed) or X (not allowed) with its top-left at [x], [y]. */
private fun drawMark(g: GuiGraphics, x: Int, y: Int, yes: Boolean, color: Int) {
    val pixels = if (yes) listOf(0 to 0, 4 to 0, 0 to 1, 4 to 1, 1 to 2, 3 to 2, 1 to 3, 3 to 3, 2 to 4)
        else listOf(0 to 0, 4 to 0, 1 to 1, 3 to 1, 2 to 2, 1 to 3, 3 to 3, 0 to 4, 4 to 4)
    pixels.forEach { (px, py) -> g.fill(x + px, y + py, x + px + 1, y + py + 1, color) }
}

/** A 7x6 check mark with a dark outline, its top-left at [x], [y]. */
private fun drawCheck(g: GuiGraphics, x: Int, y: Int, color: Int, outline: Int) {
    val pixels = listOf(6 to 0, 5 to 1, 4 to 2, 0 to 2, 1 to 3, 3 to 3, 2 to 4)
    pixels.forEach { (px, py) -> g.fill(x + px - 1, y + py - 1, x + px + 2, y + py + 2, outline) }
    pixels.forEach { (px, py) -> g.fill(x + px, y + py, x + px + 1, y + py + 1, color) }
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
