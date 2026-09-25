package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.api.ui.experimental.ExperimentalMbcUi
import jbro.cobblemon.morebattlecontent.api.ui.experimental.MbcUiContractValidator
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueBadgeState
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeContract
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixture
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixtureCatalog
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueNextChallenge
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

@OptIn(ExperimentalMbcUi::class)
internal class LeagueChallengeDevelopmentScreen(
    private val fixture: LeagueHomeFixture = LeagueHomeFixtureCatalog.require("badges_3")
) : Screen(text("title")), LeagueUiVerificationProbe {
    private lateinit var layout: LeagueHomeLayout
    private lateinit var challengeButton: LeagueHomeActionButton
    private val trainerModelRenderer = LeagueTrainerModelRenderer()
    private var actionStatus: Component = Component.empty()
    override var actionDispatched: Boolean = false
        private set
    override val actionAvailable: Boolean get() = fixture.challengeAvailable
    override var trainerModelRendered: Boolean = false
        private set
    override var unavailableExplanationShown: Boolean = false
        private set

    override fun init() {
        check(MbcUiContractValidator.validate(LeagueHomeContract.definition).isEmpty()) {
            "Invalid League home MbcUI contract"
        }
        layout = LeagueHomeLayout.calculate(width, height)
        val actionLabel = text(if (fixture.challengeAvailable) "challenge" else "challenge_unavailable_short")
        val actionNarration = text(if (fixture.challengeAvailable) "challenge" else "challenge_unavailable")
        challengeButton = LeagueHomeActionButton(
            layout.actionButton,
            actionLabel,
            actionNarration,
            fixture.challengeAvailable,
            ::dispatchChallenge
        )
        addRenderableWidget(challengeButton)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        graphics.flush()
        drawShell(graphics)
        drawHeader(graphics)
        drawBadges(graphics)
        drawChallenge(graphics)
        drawFooter(graphics)
        challengeButton.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawShell(graphics: GuiGraphics) {
        graphics.fill(layout.shell.left - 2, layout.shell.top - 2, layout.shell.right + 2, layout.shell.bottom + 2, SHADOW)
        graphics.fill(layout.shell.left, layout.shell.top, layout.shell.right, layout.shell.bottom, SHELL)
        border(graphics, layout.shell, BORDER)
        graphics.fill(layout.header.left, layout.header.top, layout.header.right, layout.header.bottom, HEADER)
        graphics.fill(layout.footer.left, layout.footer.top, layout.footer.right, layout.footer.bottom, FOOTER)
    }

    private fun drawHeader(graphics: GuiGraphics) {
        val rank = Component.translatable(key(fixture.rank.translationKey))
        drawBallEmblem(graphics, layout.header.left + 10, layout.header.top + 9, rankColor())
        graphics.drawString(font, title, layout.header.left + 31, layout.header.top + 8, TEXT_PRIMARY, false)
        val rankLabel = text("rank_value", rank)
        graphics.drawString(font, rankLabel, layout.header.right - 10 - font.width(rankLabel), layout.header.top + 6, rankColor(), false)
        val levelCap = fixture.levelCap?.let { Component.literal(it.toString()) } ?: text("level_cap_pending")
        val capLabel = text("level_cap_value", levelCap)
        graphics.drawString(font, capLabel, layout.header.right - 10 - font.width(capLabel), layout.header.top + 18, TEXT_MUTED, false)
    }

    private fun drawBadges(graphics: GuiGraphics) {
        panel(graphics, layout.badges)
        graphics.drawString(font, text("badges"), layout.badges.left + 7, layout.badges.top + 6, ACCENT, false)
        val progress = text("badge_progress", fixture.badgeCount)
        graphics.drawString(font, progress, layout.badges.right - 7 - font.width(progress), layout.badges.top + 6, TEXT_MUTED, false)

        val gridTop = layout.badges.top + 20
        val gridBottom = layout.badges.bottom - 6
        val cellGap = 4
        val columns = 4
        val cellWidth = (layout.badges.width - 14 - cellGap * (columns - 1)) / columns
        val cellHeight = (gridBottom - gridTop - cellGap) / 2
        fixture.badges.forEachIndexed { position, badge ->
            val column = position % columns
            val row = position / columns
            val rect = LeagueUiRect(
                left = layout.badges.left + 7 + column * (cellWidth + cellGap),
                top = gridTop + row * (cellHeight + cellGap),
                width = cellWidth,
                height = cellHeight
            )
            val fill = when (badge.state) {
                LeagueBadgeState.CLEARED -> BADGE_CLEARED
                LeagueBadgeState.CURRENT -> BADGE_CURRENT
                LeagueBadgeState.LOCKED -> BADGE_LOCKED
            }
            graphics.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
            border(graphics, rect, if (badge.state == LeagueBadgeState.CURRENT) ACCENT else BORDER_DIM)
            drawBadgeMark(graphics, rect, badge.index, badge.state)
        }
    }

    private fun drawChallenge(graphics: GuiGraphics) {
        panel(graphics, layout.challenge)
        graphics.drawString(font, text("next_challenge"), layout.challenge.left + 7, layout.challenge.top + 6, ACCENT, false)
        val portrait = layout.trainerViewport
        graphics.fill(portrait.left, portrait.top, portrait.right, portrait.bottom, PORTRAIT_BACKGROUND)
        border(graphics, portrait, BORDER_DIM)
        trainerModelRendered = trainerModelRenderer.render(graphics, portrait)

        val textLeft = portrait.right + 7
        val textWidth = (layout.challenge.right - 7 - textLeft).coerceAtLeast(36)
        val challenge = fixture.challengeTitleTranslationKey?.let(::text) ?: when (fixture.nextChallenge) {
            LeagueNextChallenge.GYM -> text("next_gym", fixture.badgeCount + 1)
            LeagueNextChallenge.POKEMON_LEAGUE -> text("pokemon_league")
            LeagueNextChallenge.COMPLETE -> text("complete")
        }
        val copyPlacement = LeagueChallengeCopyPlacement.calculate(
            titleTop = layout.challenge.top + 24,
            titleLineCount = font.split(challenge, textWidth).size
        )
        drawWrapped(graphics, challenge, textLeft, copyPlacement.titleTop, textWidth, TEXT_PRIMARY)
        drawWrapped(
            graphics,
            text(fixture.challengeDetailTranslationKey),
            textLeft,
            copyPlacement.detailTop,
            textWidth,
            TEXT_MUTED
        )
    }

    private fun drawFooter(graphics: GuiGraphics) {
        val facilityKey = if (fixture.facilitiesUnlocked) "facilities_unlocked" else "facilities_locked"
        val facilityColor = if (fixture.facilitiesUnlocked) SUCCESS else TEXT_MUTED
        val maxWidth = (layout.actionButton.left - layout.footer.left - 14).coerceAtLeast(1)
        val label = font.plainSubstrByWidth(text(facilityKey).string, maxWidth)
        graphics.drawString(font, Component.literal(label), layout.footer.left + 7, layout.footer.top + 12, facilityColor, false)
        if (actionStatus.string.isNotEmpty()) {
            val status = font.plainSubstrByWidth(actionStatus.string, maxWidth)
            graphics.drawString(font, Component.literal(status), layout.footer.left + 7, layout.footer.top + 22, ACCENT, false)
        }
    }

    private fun dispatchChallenge() {
        if (fixture.challengeAvailable) {
            actionDispatched = true
            actionStatus = text("dev_action", LeagueHomeContract.OPEN_NEXT_CHALLENGE.value)
        } else {
            unavailableExplanationShown = true
            actionStatus = text("challenge_unavailable_status")
        }
    }

    private fun drawBadgeMark(graphics: GuiGraphics, rect: LeagueUiRect, index: Int, state: LeagueBadgeState) {
        val centerX = rect.left + rect.width / 2
        val centerY = rect.top + rect.height / 2
        val size = minOf(12, rect.height - 5).coerceAtLeast(6)
        graphics.fill(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2, BADGE_MARK)
        graphics.fill(centerX - size / 2, centerY - 1, centerX + size / 2, centerY + 1, BADGE_MARK_LINE)
        val numberColor = if (state == LeagueBadgeState.LOCKED) TEXT_MUTED else TEXT_PRIMARY
        graphics.drawCenteredString(font, index.toString(), centerX, centerY - 4, numberColor)
    }

    private fun drawBallEmblem(graphics: GuiGraphics, x: Int, y: Int, color: Int) {
        graphics.fill(x, y, x + 14, y + 14, color)
        graphics.fill(x, y + 6, x + 14, y + 8, HEADER)
        graphics.fill(x + 5, y + 5, x + 9, y + 9, TEXT_PRIMARY)
        graphics.fill(x + 6, y + 6, x + 8, y + 8, HEADER)
    }

    private fun panel(graphics: GuiGraphics, rect: LeagueUiRect) {
        graphics.fill(rect.left, rect.top, rect.right, rect.bottom, PANEL)
        border(graphics, rect, BORDER_DIM)
    }

    private fun border(graphics: GuiGraphics, rect: LeagueUiRect, color: Int) {
        graphics.fill(rect.left, rect.top, rect.right, rect.top + 1, color)
        graphics.fill(rect.left, rect.bottom - 1, rect.right, rect.bottom, color)
        graphics.fill(rect.left, rect.top, rect.left + 1, rect.bottom, color)
        graphics.fill(rect.right - 1, rect.top, rect.right, rect.bottom, color)
    }

    private fun drawWrapped(
        graphics: GuiGraphics,
        component: Component,
        x: Int,
        y: Int,
        width: Int,
        color: Int
    ) {
        font.split(component, width).forEachIndexed { index, line ->
            graphics.drawString(font, line, x, y + index * 10, color, false)
        }
    }

    private fun rankColor(): Int = when (fixture.rank.ordinal) {
        0 -> RANK_POKE
        1 -> RANK_GREAT
        2 -> RANK_ULTRA
        3 -> RANK_MASTER
        else -> RANK_CHAMPION
    }

    companion object {
        private const val SHADOW = 0x88000000.toInt()
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

private class LeagueHomeActionButton(
    bounds: LeagueUiRect,
    message: Component,
    private val narrationMessage: Component,
    private val available: Boolean,
    private val press: () -> Unit
) : AbstractButton(bounds.left, bounds.top, bounds.width, bounds.height, message) {
    override fun onPress() = press()

    override fun createNarrationMessage(): MutableComponent = wrapDefaultNarrationMessage(narrationMessage)

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val background = when {
            !available -> 0xFF26313D.toInt()
            isHoveredOrFocused -> 0xFFB98A32.toInt()
            else -> 0xFF8C6526.toInt()
        }
        val border = if (isHoveredOrFocused && available) 0xFFFFE09A.toInt() else 0xFFD1A451.toInt()
        graphics.fill(x, y, x + width, y + height, background)
        graphics.fill(x, y, x + width, y + 1, border)
        graphics.fill(x, y + height - 1, x + width, y + height, border)
        graphics.fill(x, y, x + 1, y + height, border)
        graphics.fill(x + width - 1, y, x + width, y + height, border)
        val font = Minecraft.getInstance().font
        val label = font.plainSubstrByWidth(message.string, (width - 8).coerceAtLeast(1))
        graphics.drawCenteredString(
            font,
            Component.literal(label),
            x + width / 2,
            y + (height - 8) / 2,
            if (available) 0xFFFFFFFF.toInt() else 0xFF778392.toInt()
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
