package jbro.cobblemon.battleui.extended.ui.champions

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import jbro.cobblemon.battleui.extended.BattleStateTracker
import jbro.cobblemon.battleui.extended.TeamIndicatorUI
import jbro.cobblemon.battleui.extended.UIUtils
import jbro.cobblemon.battleui.extended.PanelConfig
import jbro.cobblemon.battleui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.battleui.extended.ui.shared.BattleStatusPalette
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurface
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptPortraits
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptSpeaker
import jbro.cobblemon.battleui.navigation.UiRect
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.battleui.extended.ui.shared.BattleUiTheme
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.UUID
import net.minecraft.util.Identifier

/**
 * The battle information window: the player's side, the field and the opponent's side in three columns. It is laid
 * out at the GUI's own scale by [ChampionsInfoLayout], so text is drawn on whole pixels and stays sharp; a single
 * active Pokemon gets a detailed card, two or three get compact ones. The window rises into place when it opens.
 */
object ChampionsBattleInfoOverlay {
    private const val MAX_ACTIVE_PER_SIDE = 3
    private const val LINE = 10
    private const val OPEN_SECONDS = 0.18
    private const val MEDIUM_CARD_HEIGHT = 66

    private val STAT_ORDER = listOf(
        BattleStateTracker.BattleStat.ATTACK,
        BattleStateTracker.BattleStat.DEFENSE,
        BattleStateTracker.BattleStat.SPECIAL_ATTACK,
        BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
        BattleStateTracker.BattleStat.SPEED,
        BattleStateTracker.BattleStat.ACCURACY,
        BattleStateTracker.BattleStat.EVASION
    )

    private var activeAllies: List<PokemonEntry> = emptyList()
    private var activeOpponents: List<PokemonEntry> = emptyList()
    private var allyTeam: List<TeamIndicatorUI.TeamPreview> = emptyList()
    private var opponentTeam: List<TeamIndicatorUI.TeamPreview> = emptyList()
    private var effects: List<EffectRow> = emptyList()

    private data class PokemonEntry(
        val uuid: UUID,
        val name: String,
        val hpPercent: Float,
        val status: Status?,
        val level: Int?,
        val speciesIdentifier: Identifier?,
        val aspects: Set<String>
    )

    private data class EffectRow(
        val group: String,
        val name: String,
        val turns: String?,
        val opponent: Boolean = false
    )

    fun clear() {
        activeAllies = emptyList()
        activeOpponents = emptyList()
        allyTeam = emptyList()
        opponentTeam = emptyList()
        effects = emptyList()
    }

    private var openedNanos = 0L

    fun onOpened() {
        openedNanos = System.nanoTime()
    }

    /** Synthetic data, using the production renderer; never changes a live battle. */
    internal fun renderPreview(context: DrawContext, activeCount: Int = 1) {
        check(net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment)
        require(activeCount in 1..MAX_ACTIVE_PER_SIDE)
        val savedAllies = activeAllies
        val savedOpponents = activeOpponents
        try {
            fun entry(species: String, hp: Float) = PokemonEntry(
                UUID.nameUUIDFromBytes(species.toByteArray()),
                Text.translatable("cobblemon.species.$species.name").string,
                hp, null, 50, Identifier.of("cobblemon", species), emptySet()
            )
            activeAllies = listOf(entry("pikachu", .72f), entry("bulbasaur", .8f), entry("eevee", .5f)).take(activeCount)
            activeOpponents = listOf(entry("charizard", .36f), entry("venusaur", .7f), entry("blastoise", .2f)).take(activeCount)
            render(context)
        } finally {
            activeAllies = savedAllies
            activeOpponents = savedOpponents
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun sync(
        playerSide: ClientBattleSide,
        opponentSide: ClientBattleSide,
        playerUuid: UUID,
        isSpectating: Boolean
    ) {
        activeAllies = playerSide.activeClientBattlePokemon
            .mapNotNull { it.battlePokemon }
            .map(::fromClientBattlePokemon)
        activeOpponents = opponentSide.activeClientBattlePokemon
            .mapNotNull { it.battlePokemon }
            .map(::fromClientBattlePokemon)
        if (PanelConfig.enableTeamIndicatorsEffective) {
            val teams = TeamIndicatorUI.modalTeams(playerSide, opponentSide, playerUuid, isSpectating)
            allyTeam = teams.first
            opponentTeam = teams.second
        } else {
            allyTeam = emptyList()
            opponentTeam = emptyList()
        }
        effects = collectEffects()
    }

    @Suppress("UNUSED_PARAMETER")
    fun handleKeyPressed(keyCode: Int, scanCode: Int): Boolean = true

    fun render(context: DrawContext) {
        val mc = MinecraftClient.getInstance()
        val layout = ChampionsInfoLayout.calculate(mc.window.scaledWidth, mc.window.scaledHeight)
        val opened = ((System.nanoTime() - openedNanos) / 1e9 / OPEN_SECONDS).toFloat().coerceIn(0f, 1f)
        // Whole-pixel rise, so the text never sits between pixels while the window settles.
        val rise = ((1f - opened) * (1f - opened) * 10f).toInt()
        context.matrices.push()
        context.matrices.translate(0.0, rise.toDouble(), UIUtils.MODAL_Z_OFFSET)
        drawWindow(context, layout)
        drawSide(context, layout.ally, activeAllies, allyTeam, opponent = false)
        drawField(context, layout.field)
        drawSide(context, layout.opponent, activeOpponents, opponentTeam, opponent = true)
        context.matrices.pop()
    }

    private fun drawWindow(context: DrawContext, layout: ChampionsInfoLayout.Result) {
        val window = layout.window
        val corners = BattleCornerCuts(10, 10, 10, 10)
        BattleSurfaceRenderer.draw(context, window.x(), window.y() + 3, window.width(), window.height(),
            BattleSurface(0x55000000, cornerCuts = corners))
        BattleSurfaceRenderer.draw(context, window.x(), window.y(), window.width(), window.height(),
            BattleUiTheme.shell.copy(cornerCuts = corners))
        val font = MinecraftClient.getInstance().textRenderer
        BattleSurfaceRenderer.capsule(context, window.x() + 9, window.y() + 6, 3, 9, BattleUiTheme.CYAN)
        text(context, tr("cobblemon_battle_ui.champions.title"), window.x() + 16, window.y() + 7, WHITE)
        val close = tr("cobblemon_battle_ui.champions.close")
        text(context, close, window.x() + window.width() - 10 - font.getWidth(close), window.y() + 7, TEXT_DIM)
    }

    private fun drawSide(context: DrawContext, rect: UiRect, entries: List<PokemonEntry>,
                         team: List<TeamIndicatorUI.TeamPreview>, opponent: Boolean) {
        val accent = if (opponent) BattleUiTheme.PURPLE else BattleUiTheme.CYAN
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            BattleUiTheme.panel.copy(top = if (opponent) ENEMY_GLASS else ALLY_GLASS, bottom = BattleUiTheme.PANEL,
                cornerCuts = BattleCornerCuts(7, 7, 7, 7)))
        text(context, tr(if (opponent) "cobblemon_battle_ui.champions.opponent_active"
            else "cobblemon_battle_ui.champions.your_active"), rect.x() + 7, rect.y() + 6, accent)
        val teamBottom = drawTeam(context, team, entries, rect.x() + 7, rect.y() + 18, rect.width() - 14, accent, opponent)
        val shown = entries.take(MAX_ACTIVE_PER_SIDE)
        val top = teamBottom + 5
        val bottom = rect.y() + rect.height() - 5
        if (shown.isEmpty()) {
            wrapped(context, tr("cobblemon_battle_ui.champions.no_active"), rect.x() + 8, top + 8, rect.width() - 16, TEXT_DIM)
            return
        }
        val gap = 4
        val cardHeight = (bottom - top - gap * (shown.size - 1)) / shown.size
        shown.forEachIndexed { index, entry ->
            val card = UiRect(rect.x() + 5, top + index * (cardHeight + gap), rect.width() - 10, cardHeight)
            when {
                shown.size == 1 -> drawDetailedCard(context, card, entry, opponent)
                cardHeight >= MEDIUM_CARD_HEIGHT -> drawMediumCard(context, card, entry, opponent)
                else -> drawCompactCard(context, card, entry, opponent)
            }
        }
    }

    /** The side's party as small portrait chips: those in battle are ringed, fainted ones dimmed. */
    private fun drawTeam(context: DrawContext, team: List<TeamIndicatorUI.TeamPreview>, active: List<PokemonEntry>,
                         x: Int, y: Int, width: Int, accent: Int, opponent: Boolean): Int {
        if (team.isEmpty()) return y
        val chip = 16
        val step = minOf(chip + 3, width / 6)
        val activeIds = active.map { it.uuid }.toSet()
        val corners = BattleCornerCuts(5, 5, 5, 5)
        team.take(6).forEachIndexed { index, pokemon ->
            val chipX = x + index * step
            if (pokemon.uuid in activeIds) {
                BattleSurfaceRenderer.draw(context, chipX - 1, y - 1, chip + 2, chip + 2,
                    BattleSurface(accent, cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
            }
            BattleSurfaceRenderer.draw(context, chipX, y, chip, chip, BattleSurface(BattleUiTheme.PANEL_ALT, cornerCuts = corners))
            // The player's own party arrives as renderable Pokemon; tracked opponents as species ids.
            val species = pokemon.speciesIdentifier ?: pokemon.renderablePokemon?.species?.resourceIdentifier
            val aspects = pokemon.renderablePokemon?.aspects ?: pokemon.aspects
            species?.let {
                TranscriptPortraits.draw(context, TranscriptSpeaker(pokemon.uuid, !opponent, "", Text.empty(),
                    it, aspects), chipX, y, chip)
            }
            if (pokemon.isKO) {
                BattleSurfaceRenderer.draw(context, chipX, y, chip, chip, BattleSurface(0xA0101624.toInt(), cornerCuts = corners))
            } else pokemon.status?.let { status ->
                BattleSurfaceRenderer.capsule(context, chipX + chip - 5, y + chip - 5, 5, 5,
                    BattleStatusPalette.background(status.showdownName))
            }
        }
        return y + chip
    }

    private fun drawDetailedCard(context: DrawContext, rect: UiRect, entry: PokemonEntry, opponent: Boolean) {
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            BattleSurface(BattleUiTheme.PANEL_ALT, cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
        val x = rect.x() + 5
        val width = rect.width() - 10
        var y = rect.y() + 5
        drawPortrait(context, entry, x, y, 26, opponent)
        drawNameLine(context, entry, x + 30, y + 2, width - 30)
        drawStatusBadge(context, entry.status, x + 30, y + 15)
        y += 31
        drawHp(context, entry.hpPercent, x, y, width)
        y += 12
        text(context, tr("cobblemon_battle_ui.champions.additional_status"), x, y, TEXT_LABEL)
        y += LINE
        y = wrapped(context, volatileText(entry.uuid), x, y, width, WHITE, maxLines = 2) + 3
        text(context, tr("cobblemon_battle_ui.champions.stat_changes"), x, y, TEXT_LABEL)
        y += LINE + 1
        drawDetailedRankRows(context, entry.uuid, x, y, width, rect.y() + rect.height() - 4)
    }

    /** Two actives per side: the compact header, then status with extra conditions, then every stat in three columns. */
    private fun drawMediumCard(context: DrawContext, rect: UiRect, entry: PokemonEntry, opponent: Boolean) {
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            BattleSurface(BattleUiTheme.PANEL_ALT, cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
        val font = MinecraftClient.getInstance().textRenderer
        val x = rect.x() + 4
        val width = rect.width() - 8
        drawPortrait(context, entry, x, rect.y() + 3, 18, opponent)
        drawNameLine(context, entry, x + 21, rect.y() + 3, width - 21)
        drawHp(context, entry.hpPercent, x + 21, rect.y() + 13, width - 21)
        val badgeRight = drawStatusBadge(context, entry.status, x, rect.y() + 25)
        val extra = BattleStateTracker.getVolatileStatuses(entry.uuid).map { it.type.displayName }
        if (extra.isNotEmpty()) {
            text(context, font.trimToWidth(extra.joinToString(" · "), x + width - badgeRight - 4),
                badgeRight + 4, rect.y() + 26, WHITE)
        }
        val stages = BattleStateTracker.getStatChanges(entry.uuid)
        val columnWidth = (width - 8) / 3
        STAT_ORDER.forEachIndexed { index, stat ->
            val stage = stages[stat] ?: 0
            val cellX = x + (index % 3) * (columnWidth + 4)
            val rowY = rect.y() + 38 + (index / 3) * LINE
            if (rowY + 8 > rect.y() + rect.height()) return@forEachIndexed
            text(context, stat.abbr, cellX, rowY, if (stage == 0) TEXT_DIM else WHITE)
            val value = stageText(stage)
            text(context, value, cellX + columnWidth - font.getWidth(value), rowY, stageColor(stage))
        }
    }

    private fun drawCompactCard(context: DrawContext, rect: UiRect, entry: PokemonEntry, opponent: Boolean) {
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            BattleSurface(BattleUiTheme.PANEL_ALT, cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
        val x = rect.x() + 4
        val width = rect.width() - 8
        drawPortrait(context, entry, x, rect.y() + 3, 18, opponent)
        drawNameLine(context, entry, x + 21, rect.y() + 3, width - 21)
        drawHp(context, entry.hpPercent, x + 21, rect.y() + 13, width - 21)
        if (rect.height() >= 36) drawCompactRankGrid(context, entry, x, rect.y() + 24, width)
    }

    private fun drawPortrait(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, size: Int, opponent: Boolean) {
        BattleSurfaceRenderer.draw(context, x, y, size, size,
            BattleSurface(BattleUiTheme.PANEL, cornerCuts = BattleCornerCuts(5, 5, 5, 5)))
        val species = entry.speciesIdentifier
        if (species == null || !TranscriptPortraits.draw(context,
                TranscriptSpeaker(entry.uuid, !opponent, "", Text.empty(), species, entry.aspects), x, y, size)) {
            text(context, "?", x + size / 2 - 2, y + size / 2 - 4, TEXT_DIM)
        }
    }

    private fun drawNameLine(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val level = entry.level?.let { "Lv.$it" }
        val levelWidth = level?.let { font.getWidth(it) + 4 } ?: 0
        text(context, font.trimToWidth(entry.name, (width - levelWidth).coerceAtLeast(0)), x, y, WHITE)
        level?.let { text(context, it, x + width - font.getWidth(it), y, TEXT_DIM) }
    }

    /** A capsule in the status's own color, or a quiet one reading "normal"; returns its right edge. */
    private fun drawStatusBadge(context: DrawContext, status: Status?, x: Int, y: Int): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val label = status?.let { TeamIndicatorUI.getStatusDisplayName(it) } ?: tr("cobblemon_battle_ui.champions.normal")
        val width = font.getWidth(label) + 8
        BattleSurfaceRenderer.capsule(context, x, y, width, 10,
            status?.let { BattleStatusPalette.background(it.showdownName) } ?: STAGE_CHIP)
        text(context, label, x + 4, y + 1, if (status != null) 0xFF182337.toInt() else TEXT_DIM)
        return x + width
    }

    private fun drawHp(context: DrawContext, ratio: Float, x: Int, y: Int, width: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val clamped = ratio.coerceIn(0f, 1f)
        val percent = "${kotlin.math.ceil(clamped * 100).toInt()}%"
        val barWidth = width - font.getWidth("100%") - 4
        val hpColor = when {
            clamped > .5f -> BattleUiTheme.GOOD
            clamped > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        BattleSurfaceRenderer.gauge(context, x, y + 1, barWidth, 6, clamped, BattleUiTheme.TRACK, hpColor)
        text(context, percent, x + width - font.getWidth(percent), y, WHITE)
    }

    /**
     * All seven stats: name, a six-step track and the stage, one per row when the card has the room and in two
     * columns without the track otherwise. Unchanged stats stay quiet.
     */
    private fun drawDetailedRankRows(context: DrawContext, uuid: UUID, x: Int, y: Int, width: Int, bottom: Int) {
        val stages = BattleStateTracker.getStatChanges(uuid)
        val font = MinecraftClient.getInstance().textRenderer
        if (bottom - y >= STAT_ORDER.size * LINE) {
            val labelWidth = STAT_ORDER.maxOf { font.getWidth(it.abbr) } + 6
            STAT_ORDER.forEachIndexed { index, stat ->
                val stage = stages[stat] ?: 0
                val rowY = y + index * LINE
                text(context, stat.abbr, x, rowY, if (stage == 0) TEXT_DIM else WHITE)
                drawRankTrack(context, x + labelWidth, rowY + 1, stage, 5, 2)
                val value = stageText(stage)
                text(context, value, x + width - font.getWidth(value), rowY, stageColor(stage))
            }
            return
        }
        val columnWidth = (width - 6) / 2
        STAT_ORDER.forEachIndexed { index, stat ->
            val stage = stages[stat] ?: 0
            val cellX = x + (index % 2) * (columnWidth + 6)
            val rowY = y + (index / 2) * LINE
            text(context, stat.abbr, cellX, rowY, if (stage == 0) TEXT_DIM else WHITE)
            val value = stageText(stage)
            text(context, value, cellX + columnWidth - font.getWidth(value), rowY, stageColor(stage))
        }
    }

    /** Only the changed stats, as chips beside the status; a card with none shows just its status. */
    private fun drawCompactRankGrid(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        var chipX = drawStatusBadge(context, entry.status, x, y) + 3
        val stages = BattleStateTracker.getStatChanges(entry.uuid)
        for (stat in STAT_ORDER) {
            val stage = stages[stat] ?: 0
            if (stage == 0) continue
            val label = stat.abbr + stageText(stage)
            val chipWidth = font.getWidth(label) + 8
            if (chipX + chipWidth > x + width) break
            BattleSurfaceRenderer.capsule(context, chipX, y, chipWidth, 10, STAGE_CHIP)
            text(context, label, chipX + 4, y + 1, stageColor(stage))
            chipX += chipWidth + 2
        }
    }

    private fun drawRankTrack(context: DrawContext, x: Int, y: Int, stage: Int, cell: Int, gap: Int) {
        val activeCells = kotlin.math.abs(stage).coerceAtMost(6)
        repeat(6) { index ->
            BattleSurfaceRenderer.capsule(context, x + index * (cell + gap), y + 1, cell, 5,
                if (index < activeCells) stageColor(stage) else RANK_EMPTY)
        }
    }

    private fun drawField(context: DrawContext, rect: UiRect) {
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            BattleUiTheme.panel.copy(cornerCuts = BattleCornerCuts(7, 7, 7, 7)))
        val font = MinecraftClient.getInstance().textRenderer
        text(context, font.trimToWidth(tr("cobblemon_battle_ui.champions.effects"), rect.width() - 14),
            rect.x() + 7, rect.y() + 6, WHITE)
        val top = rect.y() + 19
        if (effects.isEmpty()) {
            wrapped(context, tr("cobblemon_battle_ui.ui.no_effects"), rect.x() + 7, top + 6, rect.width() - 14, TEXT_DIM)
            return
        }
        val rowHeight = 23
        val capacity = ((rect.y() + rect.height() - 4 - top) / (rowHeight + 2)).coerceAtLeast(1)
        val overflow = effects.size > capacity
        val shown = effects.take(if (overflow) capacity - 1 else capacity)
        shown.forEachIndexed { index, effect ->
            val rowY = top + index * (rowHeight + 2)
            val edge = if (effect.opponent) BattleUiTheme.PURPLE else BattleUiTheme.CYAN
            BattleSurfaceRenderer.draw(context, rect.x() + 4, rowY, rect.width() - 8, rowHeight,
                BattleSurface(if (effect.opponent) OPPONENT_ROW else BattleUiTheme.PANEL_ALT,
                    cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
            BattleSurfaceRenderer.capsule(context, rect.x() + 6, rowY + 4, 3, rowHeight - 8, edge)
            val textX = rect.x() + 12
            val right = rect.x() + rect.width() - 8
            text(context, font.trimToWidth(effect.group, right - textX), textX, rowY + 2, TEXT_LABEL)
            val turns = effect.turns
            val turnsWidth = turns?.let { font.getWidth(it) + 8 } ?: 0
            text(context, font.trimToWidth(effect.name, (right - textX - turnsWidth).coerceAtLeast(0)), textX, rowY + 12, WHITE)
            if (turns != null) {
                BattleSurfaceRenderer.capsule(context, right - turnsWidth + 2, rowY + 11, turnsWidth, 10, STAGE_CHIP)
                text(context, turns, right - turnsWidth + 6, rowY + 12, WHITE)
            }
        }
        if (overflow) {
            val rowY = top + shown.size * (rowHeight + 2)
            val more = tr("cobblemon_battle_ui.champions.more_effects", effects.size - shown.size)
            text(context, font.trimToWidth(more, rect.width() - 14), rect.x() + 7, rowY + 7, TEXT_DIM)
        }
    }

    private fun volatileText(uuid: UUID): String {
        val statuses = BattleStateTracker.getVolatileStatuses(uuid)
            .map { state -> state.type.displayName }
        val text = if (statuses.isEmpty()) {
            tr("cobblemon_battle_ui.champions.no_additional_status")
        } else {
            statuses.joinToString(" · ")
        }
        return text
    }

    private fun stageText(stage: Int): String = if (stage > 0) "+$stage" else stage.toString()

    private fun stageColor(stage: Int): Int = when {
        stage > 0 -> BOOST
        stage < 0 -> DROP
        else -> TEXT_DIM
    }

    private fun collectEffects(): List<EffectRow> = buildList {
        BattleStateTracker.weather?.let {
            add(EffectRow(tr("cobblemon_battle_ui.champions.weather"), it.type.displayName,
                turnText(BattleStateTracker.getWeatherTurnsRemaining())))
        }
        BattleStateTracker.terrain?.let {
            add(EffectRow(tr("cobblemon_battle_ui.champions.terrain"), it.type.displayName,
                turnText(BattleStateTracker.getTerrainTurnsRemaining())))
        }
        BattleStateTracker.getFieldConditions().forEach { (type, _) ->
            add(EffectRow(tr("cobblemon_battle_ui.champions.special_rule"), type.displayName,
                turnText(BattleStateTracker.getFieldConditionTurnsRemaining(type))))
        }
        BattleStateTracker.getPlayerSideConditions().forEach { (type, state) ->
            val suffix = if (state.stacks > 1) " x${state.stacks}" else ""
            add(EffectRow(tr("cobblemon_battle_ui.champions.ally_side"), type.displayName + suffix,
                turnText(BattleStateTracker.getSideConditionTurnsRemaining(true, type))))
        }
        BattleStateTracker.getOpponentSideConditions().forEach { (type, state) ->
            val suffix = if (state.stacks > 1) " x${state.stacks}" else ""
            add(EffectRow(tr("cobblemon_battle_ui.champions.opponent_side"), type.displayName + suffix,
                turnText(BattleStateTracker.getSideConditionTurnsRemaining(false, type)), opponent = true))
        }
    }

    private fun fromClientBattlePokemon(pokemon: ClientBattlePokemon): PokemonEntry {
        val hp = if (pokemon.isHpFlat && pokemon.maxHp > 0) pokemon.hpValue / pokemon.maxHp else pokemon.hpValue
        return PokemonEntry(
            uuid = pokemon.uuid,
            name = pokemon.displayName.string,
            hpPercent = hp.coerceIn(0f, 1f),
            status = pokemon.status,
            level = pokemon.properties.level,
            speciesIdentifier = pokemon.properties.species?.let { Identifier.of("cobblemon", it) },
            aspects = pokemon.state.currentAspects
        )
    }

    private fun turnText(turns: String?): String? = turns?.let { tr("cobblemon_battle_ui.champions.turns", it) }
    private fun tr(key: String, vararg args: Any): String = Text.translatable(key, *args).string

    /** Text at the GUI's own scale, on whole pixels. */
    private fun text(context: DrawContext, value: String, x: Int, y: Int, color: Int) {
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y, color, false)
    }

    /** Wraps [value] to [width], at most [maxLines] lines; returns the y below the last line. */
    private fun wrapped(context: DrawContext, value: String, x: Int, y: Int, width: Int, color: Int, maxLines: Int = 3): Int {
        val font = MinecraftClient.getInstance().textRenderer
        val lines = font.wrapLines(Text.literal(value), width.coerceAtLeast(1)).take(maxLines)
        lines.forEachIndexed { index, line -> context.drawText(font, line, x, y + index * LINE, color, false) }
        return y + lines.size * LINE
    }

    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = UIUtils.color(r, g, b, a)

    private val WHITE = BattleUiTheme.TEXT
    private val TEXT_DIM = BattleUiTheme.MUTED
    private val TEXT_LABEL = BattleUiTheme.CYAN
    private val ALLY_GLASS = color(27, 61, 78, 242)
    private val ENEMY_GLASS = color(57, 36, 79, 242)
    private val OPPONENT_ROW = color(44, 28, 53, 240)
    private val STAGE_CHIP = 0xFF22354C.toInt()
    private val BOOST = BattleUiTheme.GOOD
    private val DROP = BattleUiTheme.DANGER
    private val RANK_EMPTY = BattleUiTheme.BORDER
}
