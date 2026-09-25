package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.StackLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import jbro.cobblemon.morebattlecontent.api.ui.experimental.ExperimentalMbcUi
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiContractValidator
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueBadgeState
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeContract
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixture
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixtureCatalog
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueNextChallenge
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

@OptIn(ExperimentalMbcUi::class)
internal class LeagueChallengeOwoSpikeScreen(
    private val fixture: LeagueHomeFixture = LeagueHomeFixtureCatalog.require("badges_3")
) : BaseOwoScreen<StackLayout>(text("title")), LeagueUiVerificationProbe {
    private var statusLabel: LabelComponent? = null
    override var actionDispatched: Boolean = false
        private set

    override fun createAdapter(): OwoUIAdapter<StackLayout> {
        val root = Containers.stack(Sizing.fill(100), Sizing.fill(100))
        return LeagueNarratingOwoAdapter(0, 0, width, height, root, title).also { adapter ->
            addRenderableWidget(adapter)
            focused = adapter
        }
    }

    override fun build(root: StackLayout) {
        check(MbcUiContractValidator.validate(LeagueHomeContract.definition).isEmpty()) {
            "Invalid League home MbcUI contract"
        }
        val layout = LeagueHomeLayout.calculate(width, height)
        root.surface(Surface.flat(BACKDROP))

        root.child(rectangle(layout.shell, SHELL, BORDER))
        root.child(rectangle(layout.header, HEADER))
        root.child(rectangle(layout.footer, FOOTER))
        root.child(rectangle(layout.badges, PANEL, BORDER_DIM))
        root.child(rectangle(layout.challenge, PANEL, BORDER_DIM))

        val rank = Component.translatable(key(fixture.rank.translationKey))
        addBallEmblem(root, layout.header.left + 10, layout.header.top + 9, rankColor())
        root.child(label(title, layout.header.left + 31, layout.header.top + 8, 180, TEXT_PRIMARY))

        val rankLabel = text("rank_value", rank)
        val font = Minecraft.getInstance().font
        root.child(label(rankLabel, layout.header.right - 10 - font.width(rankLabel), layout.header.top + 6, font.width(rankLabel), rankColor()))
        val levelCap = fixture.levelCap?.let { Component.literal(it.toString()) } ?: text("level_cap_pending")
        val capLabel = text("level_cap_value", levelCap)
        root.child(label(capLabel, layout.header.right - 10 - font.width(capLabel), layout.header.top + 18, font.width(capLabel), TEXT_MUTED))

        root.child(label(text("badges"), layout.badges.left + 7, layout.badges.top + 6, 90, ACCENT))
        val progress = text("badge_progress", fixture.badgeCount)
        root.child(label(progress, layout.badges.right - 7 - font.width(progress), layout.badges.top + 6, font.width(progress), TEXT_MUTED))
        addBadges(root, layout)

        root.child(label(text("next_challenge"), layout.challenge.left + 7, layout.challenge.top + 6, layout.challenge.width - 14, ACCENT))
        addChallenge(root, layout)

        val facilityKey = if (fixture.facilitiesUnlocked) "facilities_unlocked" else "facilities_locked"
        val facilityColor = if (fixture.facilitiesUnlocked) SUCCESS else TEXT_MUTED
        val footerTextWidth = (layout.actionButton.left - layout.footer.left - 14).coerceAtLeast(1)
        root.child(label(text(facilityKey), layout.footer.left + 7, layout.footer.top + 12, footerTextWidth, facilityColor))
        statusLabel = label(Component.empty(), layout.footer.left + 7, layout.footer.top + 22, footerTextWidth, ACCENT).also(root::child)

        root.child(
            Components.button(text("challenge")) {
                actionDispatched = true
                statusLabel?.text(text("dev_action", LeagueHomeContract.OPEN_NEXT_CHALLENGE.value))
            }.apply {
                sizing(Sizing.fixed(layout.actionButton.width), Sizing.fixed(layout.actionButton.height))
                positioning(Positioning.absolute(layout.actionButton.left, layout.actionButton.top))
                renderer(ButtonComponent.Renderer.flat(BUTTON, BUTTON_HOVER, BUTTON_DISABLED))
                active(fixture.challengeAvailable)
                textShadow(false)
            }
        )
    }

    private fun addBadges(root: StackLayout, layout: LeagueHomeLayout) {
        val gridTop = layout.badges.top + 20
        val gridBottom = layout.badges.bottom - 6
        val cellGap = 4
        val columns = 4
        val cellWidth = (layout.badges.width - 14 - cellGap * (columns - 1)) / columns
        val cellHeight = (gridBottom - gridTop - cellGap) / 2
        fixture.badges.forEachIndexed { position, badge ->
            val rect = LeagueUiRect(
                left = layout.badges.left + 7 + (position % columns) * (cellWidth + cellGap),
                top = gridTop + (position / columns) * (cellHeight + cellGap),
                width = cellWidth,
                height = cellHeight
            )
            val fill = when (badge.state) {
                LeagueBadgeState.CLEARED -> BADGE_CLEARED
                LeagueBadgeState.CURRENT -> BADGE_CURRENT
                LeagueBadgeState.LOCKED -> BADGE_LOCKED
            }
            root.child(rectangle(rect, fill, if (badge.state == LeagueBadgeState.CURRENT) ACCENT else BORDER_DIM))
            val size = minOf(12, rect.height - 5).coerceAtLeast(6)
            val mark = LeagueUiRect(rect.left + (rect.width - size) / 2, rect.top + (rect.height - size) / 2, size, size)
            root.child(rectangle(mark, BADGE_MARK))
            root.child(rectangle(LeagueUiRect(mark.left, mark.top + size / 2 - 1, size, 2), BADGE_MARK_LINE))
            root.child(centeredLabel(Component.literal(badge.index.toString()), mark, if (badge.state == LeagueBadgeState.LOCKED) TEXT_MUTED else TEXT_PRIMARY))
        }
    }

    private fun addChallenge(root: StackLayout, layout: LeagueHomeLayout) {
        val portraitSize = minOf(48, layout.challenge.height - 31, layout.challenge.width / 3).coerceAtLeast(24)
        val portrait = LeagueUiRect(layout.challenge.left + 7, layout.challenge.top + 21, portraitSize, portraitSize)
        root.child(rectangle(portrait, PORTRAIT_BACKGROUND, BORDER_DIM))
        val centerX = portrait.left + portrait.width / 2
        val head = (portrait.width / 7).coerceAtLeast(3)
        val headTop = portrait.top + 7
        root.child(rectangle(LeagueUiRect(centerX - head, headTop, head * 2, head * 2), SILHOUETTE))
        root.child(rectangle(LeagueUiRect(centerX - head * 2, headTop + head * 2 + 2, head * 4, portrait.bottom - 6 - (headTop + head * 2 + 2)), SILHOUETTE))

        val textLeft = portrait.right + 7
        val textWidth = (layout.challenge.right - 7 - textLeft).coerceAtLeast(36)
        val challenge = when (fixture.nextChallenge) {
            LeagueNextChallenge.GYM -> text("next_gym", fixture.badgeCount + 1)
            LeagueNextChallenge.POKEMON_LEAGUE -> text("pokemon_league")
            LeagueNextChallenge.COMPLETE -> text("complete")
        }
        root.child(label(challenge, textLeft, layout.challenge.top + 24, textWidth, TEXT_PRIMARY))
        root.child(label(text("fixture_notice"), textLeft, layout.challenge.top + 47, textWidth, TEXT_MUTED))
    }

    private fun addBallEmblem(root: StackLayout, x: Int, y: Int, color: Int) {
        root.child(rectangle(LeagueUiRect(x, y, 14, 14), color))
        root.child(rectangle(LeagueUiRect(x, y + 6, 14, 2), HEADER))
        root.child(rectangle(LeagueUiRect(x + 5, y + 5, 4, 4), TEXT_PRIMARY))
        root.child(rectangle(LeagueUiRect(x + 6, y + 6, 2, 2), HEADER))
    }

    private fun rectangle(rect: LeagueUiRect, fill: Int, outline: Int? = null): StackLayout =
        Containers.stack(Sizing.fixed(rect.width), Sizing.fixed(rect.height)).apply {
            positioning(Positioning.absolute(rect.left, rect.top))
            surface(outline?.let { Surface.flat(fill).and(Surface.outline(it)) } ?: Surface.flat(fill))
        }

    private fun label(value: Component, x: Int, y: Int, maxWidth: Int, color: Int): LabelComponent =
        Components.label(value).apply {
            sizing(Sizing.fixed(maxWidth), Sizing.content())
            positioning(Positioning.absolute(x, y))
            maxWidth(maxWidth)
            color(Color.ofArgb(color))
            shadow(false)
        }

    private fun centeredLabel(value: Component, rect: LeagueUiRect, color: Int): LabelComponent =
        Components.label(value).apply {
            sizing(Sizing.fixed(rect.width), Sizing.fixed(rect.height))
            positioning(Positioning.absolute(rect.left, rect.top))
            horizontalTextAlignment(HorizontalAlignment.CENTER)
            verticalTextAlignment(VerticalAlignment.CENTER)
            color(Color.ofArgb(color))
            shadow(false)
        }

    private fun rankColor(): Int = when (fixture.rank.ordinal) {
        0 -> RANK_POKE
        1 -> RANK_GREAT
        2 -> RANK_ULTRA
        3 -> RANK_MASTER
        else -> RANK_CHAMPION
    }

    companion object {
        private const val BACKDROP = 0xB0000000.toInt()
        private const val SHELL = 0xFF101722.toInt()
        private const val HEADER = 0xFF182638.toInt()
        private const val FOOTER = 0xFF121D2A.toInt()
        private const val PANEL = 0xFF162131.toInt()
        private const val BORDER = 0xFF57718C.toInt()
        private const val BORDER_DIM = 0xFF2D4157.toInt()
        private const val ACCENT = 0xFFF3C969.toInt()
        private const val TEXT_PRIMARY = 0xFFF5F7FA.toInt()
        private const val TEXT_MUTED = 0xFF9BAABD.toInt()
        private const val SUCCESS = 0xFF75D69C.toInt()
        private const val BADGE_CLEARED = 0xFF214B3E.toInt()
        private const val BADGE_CURRENT = 0xFF544725.toInt()
        private const val BADGE_LOCKED = 0xFF1C2734.toInt()
        private const val BADGE_MARK = 0xFFD8E1EC.toInt()
        private const val BADGE_MARK_LINE = 0xFF25374A.toInt()
        private const val PORTRAIT_BACKGROUND = 0xFF0E1621.toInt()
        private const val SILHOUETTE = 0xFF60758C.toInt()
        private const val BUTTON = 0xFF8C6526.toInt()
        private const val BUTTON_HOVER = 0xFFB98A32.toInt()
        private const val BUTTON_DISABLED = 0xFF26313D.toInt()
        private const val RANK_POKE = 0xFFE5E7EB.toInt()
        private const val RANK_GREAT = 0xFF5CB8FF.toInt()
        private const val RANK_ULTRA = 0xFFFFD45C.toInt()
        private const val RANK_MASTER = 0xFFC98CFF.toInt()
        private const val RANK_CHAMPION = 0xFFFF8E66.toInt()

        private fun key(suffix: String): String =
            "screen.cobblemon_more_battle_content_league_challenge.home.$suffix"

        private fun text(suffix: String, vararg args: Any): Component = Component.translatable(key(suffix), *args)
    }
}

private class LeagueNarratingOwoAdapter(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    root: StackLayout,
    private val fallbackTitle: Component
) : OwoUIAdapter<StackLayout>(x, y, width, height, root) {
    override fun narrationPriority(): NarratableEntry.NarrationPriority =
        focusedNarratable()?.narrationPriority() ?: NarratableEntry.NarrationPriority.NONE

    override fun updateNarration(output: NarrationElementOutput) {
        val focused = focusedNarratable()
        if (focused != null) {
            focused.updateNarration(output)
        } else {
            output.add(NarratedElementType.TITLE, fallbackTitle)
        }
    }

    private fun focusedNarratable(): NarratableEntry? =
        rootComponent.focusHandler()?.focused() as? NarratableEntry
}
