package jbro.cobblemon.battleui.extended.ui.champions

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import jbro.cobblemon.battleui.extended.BattleStateTracker
import jbro.cobblemon.battleui.extended.TeamIndicatorUI
import jbro.cobblemon.battleui.extended.UIUtils
import jbro.cobblemon.battleui.extended.PanelConfig
import jbro.cobblemon.battleui.extended.PixelTextLayout
import jbro.cobblemon.battleui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.battleui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.UUID
import net.minecraft.util.Identifier
import kotlin.math.min

/**
 * Large battle-status modal with vertical team previews and active Pokemon portraits.
 */
object ChampionsBattleInfoOverlay {
    private const val BASE_W = 960
    private const val BASE_H = 440
    private const val FRAME_TOP = 12
    private const val TITLE_HEIGHT = 40
    private const val TITLE_TOP = FRAME_TOP - TITLE_HEIGHT / 2
    private const val TEAM_X_LEFT = 18
    private const val SIDE_X_LEFT = 66
    private const val FIELD_X = 340
    private const val SIDE_X_RIGHT = 642
    private const val TEAM_X_RIGHT = 900
    private const val TEAM_W = 42
    private const val SIDE_W = 252
    private const val FIELD_W = 280
    private const val PANEL_Y = 62
    private const val PANEL_H = 344
    private const val MAX_ACTIVE_PER_SIDE = 3
    private const val COMPACT_CONDITIONS_MIN_HEIGHT = 136

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

    fun onOpened() = Unit

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
        val screenWidth = mc.window.scaledWidth
        val screenHeight = mc.window.scaledHeight
        val scale = min(
            (screenWidth - 16f) / BASE_W,
            (screenHeight - 16f) / BASE_H
        ).coerceAtMost(1.25f)
        val originX = (screenWidth - BASE_W * scale) / 2f
        val originY = ((screenHeight - BASE_H * scale) / 2f).coerceAtLeast(-TITLE_TOP * scale)

        context.fill(0, 0, screenWidth, screenHeight, color(3, 6, 18, 106))
        context.matrices.push()
        context.matrices.translate(originX.toDouble(), originY.toDouble(), UIUtils.MODAL_Z_OFFSET)
        context.matrices.scale(scale, scale, 1f)
        drawOverlay(context)
        context.matrices.pop()
    }

    private fun drawOverlay(context: DrawContext) {
        drawFrame(context)
        drawTeamRail(context, allyTeam, TEAM_X_LEFT, false)
        drawSidePanel(context, activeAllies, SIDE_X_LEFT, false)
        drawEffectsPanel(context, FIELD_X)
        drawSidePanel(context, activeOpponents, SIDE_X_RIGHT, true)
        drawTeamRail(context, opponentTeam, TEAM_X_RIGHT, true)
        drawTextCentered(context, tr("cobblemon_battle_ui.champions.close"), BASE_W / 2, 417, WHITE, 0.95f)
    }

    private fun drawFrame(context: DrawContext) {
        BattleSurfaceRenderer.draw(context, 8, FRAME_TOP, BASE_W - 16, BASE_H - 20, BattleUiTheme.shell)
        BattleSurfaceRenderer.draw(context, 406, TITLE_TOP, 148, TITLE_HEIGHT, BattleUiTheme.panel.copy(border = BattleUiTheme.CYAN, cut = 5))
        drawTextCentered(context, tr("cobblemon_battle_ui.champions.title"), BASE_W / 2, TITLE_TOP + 14, WHITE, 1.2f)
    }

    private fun drawTeamRail(context: DrawContext, team: List<TeamIndicatorUI.TeamPreview>, x: Int, opponent: Boolean) {
        val accent = if (opponent) MAGENTA_EDGE else VIOLET_EDGE
        BattleSurfaceRenderer.draw(context, x, PANEL_Y, TEAM_W, PANEL_H, BattleUiTheme.panel)
        team.take(6).forEachIndexed { index, pokemon ->
            val slotY = PANEL_Y + 16 + index * 52
            context.fill(x + 3, slotY, x + TEAM_W - 3, slotY + 42, BattleUiTheme.PANEL_ALT)
            context.fill(x + 3, slotY, x + 5, slotY + 42, accent)
            PokemonModelRenderer.drawPokemonModel(
                context, x + 7, slotY + 5, 30, pokemon.renderablePokemon,
                pokemon.speciesIdentifier, pokemon.aspects, pokemon.uuid, pokemon.isKO,
                pokemon.status, !opponent, { it }, 1f
            )
        }
    }

    private fun drawSidePanel(
        context: DrawContext,
        entries: List<PokemonEntry>,
        x: Int,
        opponent: Boolean
    ) {
        val accent = if (opponent) MAGENTA_EDGE else VIOLET_EDGE
        val header = if (opponent) ENEMY_GLASS else ALLY_GLASS
        val title = tr(
            if (opponent) "cobblemon_battle_ui.champions.opponent_active"
            else "cobblemon_battle_ui.champions.your_active"
        )

        BattleSurfaceRenderer.draw(context, x, PANEL_Y, SIDE_W, PANEL_H, BattleUiTheme.panel)
        BattleSurfaceRenderer.draw(context, x + 1, PANEL_Y + 1, SIDE_W - 2, 37,
            BattleUiTheme.panel.copy(top = header, bottom = BattleUiTheme.PANEL, borderWidth = 0, cut = 3, corners = 3))
        context.fill(x + 7, PANEL_Y + 37, x + SIDE_W - 7, PANEL_Y + 38, accent)
        drawTextCentered(context, title, x + SIDE_W / 2, PANEL_Y + 14, WHITE, 1.15f)

        if (entries.isEmpty()) {
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.champions.no_active"),
                x + SIDE_W / 2,
                PANEL_Y + 180,
                TEXT_DIM,
                1.05f
            )
            return
        }

        val shown = entries.take(MAX_ACTIVE_PER_SIDE)
        val cardTop = PANEL_Y + 46
        val availableHeight = PANEL_H - 56
        val gap = 8
        val cardHeight = (availableHeight - gap * (shown.size - 1)) / shown.size
        shown.forEachIndexed { index, entry ->
            drawPokemonCard(context, entry, x + 10, cardTop + index * (cardHeight + gap), SIDE_W - 20, cardHeight, accent, opponent)
        }
    }

    private fun drawPokemonCard(
        context: DrawContext,
        entry: PokemonEntry,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        accent: Int,
        opponent: Boolean
    ) {
        BattleSurfaceRenderer.draw(context, x, y, width, height, BattleUiTheme.panel.copy(cut = 0))
        context.fill(x + 1, y + 8, x + 3, y + height - 8, accent)

        drawPortrait(context, entry, x + 10, y + 12, 38, opponent)
        val headerX = x + 54
        val nameWidth = if (entry.level == null) width - 68 else width - 124
        val detailed = height >= 200
        val nameScale = if (detailed) 1.2f else 1.05f
        val nameY = y + if (detailed) 13 else 9
        drawText(context, trim(context, entry.name, nameWidth, nameScale), headerX, nameY, WHITE, nameScale)
        entry.level?.let { drawTextRight(context, "Lv.$it", x + width - 14, nameY + 2, TEXT_DIM, 0.95f) }

        val status = entry.status?.let { TeamIndicatorUI.getStatusDisplayName(it) }
            ?: tr("cobblemon_battle_ui.champions.normal")
        val statusY = y + if (detailed) 42 else 29
        drawText(context, tr("cobblemon_battle_ui.champions.status"), headerX, statusY, TEXT_LABEL, 0.9f)
        drawText(context, status, headerX + 54, statusY, WHITE, 1.0f)
        drawHpBar(context, x + 14, y + if (detailed) 64 else 50,
            width - if (detailed) 28 else 76, entry.hpPercent, detailed, inlinePercent = !detailed)

        if (height >= 200) {
            drawDetailedConditions(context, entry, x, y, width)
        } else if (height >= COMPACT_CONDITIONS_MIN_HEIGHT) {
            drawCompactConditions(context, entry, x, y, width)
        }
    }

    private fun drawPortrait(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, size: Int, opponent: Boolean) {
        BattleSurfaceRenderer.draw(context, x, y, size, size, BattleUiTheme.panel.copy(cut = 4))
        PokemonModelRenderer.drawPokemonModel(
            context, x + 2, y + 2, size - 4, null, entry.speciesIdentifier,
            entry.aspects, entry.uuid, entry.hpPercent <= 0f, entry.status,
            !opponent, { it }, 1f
        )
    }

    private fun drawDetailedConditions(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        val volatileText = volatileText(context, entry.uuid, width - 28)
        drawText(context, tr("cobblemon_battle_ui.champions.additional_status"), x + 14, y + 96, TEXT_LABEL, 0.9f)
        drawText(context, volatileText, x + 14, y + 116, WHITE, 1.0f)
        drawText(context, tr("cobblemon_battle_ui.champions.stat_changes"), x + 14, y + 140, TEXT_LABEL, 0.9f)
        drawDetailedRankRows(context, entry.uuid, x + 14, y + 162, width - 28)
    }

    private fun drawCompactConditions(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        drawCompactRankGrid(context, entry.uuid, x + 14, y + 68, width - 28)
    }

    private fun drawDetailedRankRows(
        context: DrawContext,
        uuid: UUID,
        x: Int,
        y: Int,
        width: Int
    ) {
        val stages = BattleStateTracker.getStatChanges(uuid)
        STAT_ORDER.forEachIndexed { index, stat ->
            val stage = stages[stat] ?: 0
            val rowY = y + index * 18
            drawText(context, stat.displayName, x, rowY, WHITE, 0.9f)
            drawRankTrack(context, x + 72, rowY + 2, stage, 10, 6, 3)
            drawTextRight(context, stageText(stage), x + width, rowY, stageColor(stage), 0.9f)
        }
    }

    private fun drawCompactRankGrid(
        context: DrawContext,
        uuid: UUID,
        x: Int,
        y: Int,
        width: Int
    ) {
        val stages = BattleStateTracker.getStatChanges(uuid)
        val columnWidth = width / 2
        STAT_ORDER.forEachIndexed { index, stat ->
            val column = index % 2
            val row = index / 2
            val stage = stages[stat] ?: 0
            val cellX = x + column * columnWidth
            val rowY = y + row * 18
            drawText(context, stat.abbr, cellX, rowY, WHITE, 0.85f)
            drawRankTrack(context, cellX + 32, rowY + 2, stage, 5, 5, 2)
            drawTextRight(context, stageText(stage), cellX + columnWidth - 4, rowY, stageColor(stage), 0.85f)
        }
    }

    private fun drawRankTrack(
        context: DrawContext,
        x: Int,
        y: Int,
        stage: Int,
        cellWidth: Int,
        cellHeight: Int,
        gap: Int
    ) {
        val activeCells = kotlin.math.abs(stage).coerceAtMost(6)
        repeat(6) { index ->
            val cellX = x + index * (cellWidth + gap)
            val cellColor = if (index < activeCells) stageColor(stage) else RANK_EMPTY
            context.fill(cellX, y, cellX + cellWidth, y + cellHeight, cellColor)
        }
    }

    private fun drawEffectsPanel(context: DrawContext, x: Int) {
        BattleSurfaceRenderer.draw(context, x, PANEL_Y, FIELD_W, PANEL_H, BattleUiTheme.panel)
        BattleSurfaceRenderer.draw(context, x + 1, PANEL_Y + 1, FIELD_W - 2, 37,
            BattleUiTheme.panel.copy(borderWidth = 0, corners = 3))
        drawTextCentered(
            context,
            tr("cobblemon_battle_ui.champions.effects"),
            x + FIELD_W / 2,
            PANEL_Y + 14,
            WHITE,
            1.1f
        )

        if (effects.isEmpty()) {
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.ui.no_effects"),
                x + FIELD_W / 2,
                PANEL_Y + 180,
                TEXT_DIM,
                1.05f
            )
            return
        }

        val effectWindow = EffectListLayout.window(effects.size)
        effects.take(effectWindow.visibleEffectCount).forEachIndexed { index, effect ->
            val rowY = PANEL_Y + 48 + index * 36
            val rowColor = if (effect.opponent) color(44, 28, 53, 240) else BattleUiTheme.PANEL_ALT
            context.fill(x + 10, rowY, x + FIELD_W - 10, rowY + 31, rowColor)
            context.fill(x + 10, rowY, x + 13, rowY + 31, if (effect.opponent) MAGENTA_EDGE else VIOLET_EDGE)
            drawText(context, effect.group, x + 21, rowY + 6, TEXT_LABEL, 0.85f)
            drawText(context, trim(context, effect.name, 142), x + 88, rowY + 6, WHITE, 1.0f)
            effect.turns?.let { drawTextRight(context, it, x + FIELD_W - 19, rowY + 7, WHITE, 0.9f) }
        }

        if (effectWindow.hiddenEffectCount > 0) {
            val rowY = PANEL_Y + 48 + effectWindow.visibleEffectCount * 36
            context.fill(x + 10, rowY, x + FIELD_W - 10, rowY + 31, BattleUiTheme.PANEL_ALT)
            context.fill(x + 10, rowY, x + 13, rowY + 31, VIOLET_EDGE)
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.champions.more_effects", effectWindow.hiddenEffectCount),
                x + FIELD_W / 2,
                rowY + 7,
                TEXT_DIM,
                0.95f
            )
        }
    }

    private fun drawHpBar(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        hpPercent: Float,
        detailed: Boolean,
        inlinePercent: Boolean = false
    ) {
        val height = if (detailed) 12 else 9
        context.fill(x, y, x + width, y + height, BattleUiTheme.TRACK)
        val clamped = hpPercent.coerceIn(0f, 1f)
        val hpColor = when {
            clamped > .5f -> BattleUiTheme.GOOD
            clamped > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        val fillWidth = ((width - 4) * clamped).toInt()
        context.fill(x + 2, y + 2, x + 2 + fillWidth, y + height - 2, hpColor)
        drawTextRight(context, "${(clamped * 100).toInt()}%",
            x + width + if (inlinePercent) 48 else 0,
            if (inlinePercent) y - 1 else y + height + 4, WHITE, 0.9f)
    }

    private fun volatileText(context: DrawContext, uuid: UUID, width: Int): String {
        val statuses = BattleStateTracker.getVolatileStatuses(uuid)
            .map { state -> state.type.displayName }
        val text = if (statuses.isEmpty()) {
            tr("cobblemon_battle_ui.champions.no_additional_status")
        } else {
            statuses.joinToString(" · ")
        }
        return trim(context, text, width)
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
    private fun trim(context: DrawContext, text: String, width: Int, requestedScale: Float = 1f): String {
        val mc = MinecraftClient.getInstance()
        val scale = PixelTextLayout.fontScale(requestedScale, context.matrices.peek().positionMatrix.m00(), mc.window.scaleFactor.toFloat())
        return mc.textRenderer.trimToWidth(text, (width / scale).toInt())
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int, textScale: Float) =
        drawPixelText(context, text, x.toFloat(), y, color, textScale, 0f)

    private fun drawTextRight(context: DrawContext, text: String, right: Int, y: Int, color: Int, textScale: Float) {
        drawPixelText(context, text, right.toFloat(), y, color, textScale, 1f)
    }

    private fun drawTextCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int, textScale: Float) {
        drawPixelText(context, text, centerX.toFloat(), y, color, textScale, .5f)
    }

    /** Preserve minimum readable text size and snap its origin after panel/GUI transforms. */
    private fun drawPixelText(context: DrawContext, text: String, anchorX: Float, y: Int,
                              color: Int, requestedScale: Float, alignment: Float) {
        val mc = MinecraftClient.getInstance()
        val matrix = context.matrices.peek().positionMatrix
        val guiScale = mc.window.scaleFactor.toFloat()
        val scale = PixelTextLayout.fontScale(requestedScale, matrix.m00(), guiScale)
        val x = anchorX - mc.textRenderer.getWidth(text) * scale * alignment
        val snappedX = PixelTextLayout.snap(x, matrix.m30(), matrix.m00(), guiScale)
        val snappedY = PixelTextLayout.snap(y.toFloat(), matrix.m31(), matrix.m11(), guiScale)
        context.matrices.push()
        context.matrices.translate(snappedX, snappedY, 0f)
        context.matrices.scale(scale, scale, 1f)
        context.drawText(mc.textRenderer, text, 0, 0, color, false)
        context.matrices.pop()
    }

    private fun color(r: Int, g: Int, b: Int, a: Int = 255): Int = UIUtils.color(r, g, b, a)

    private val WHITE = BattleUiTheme.TEXT
    private val TEXT_DIM = BattleUiTheme.MUTED
    private val TEXT_LABEL = BattleUiTheme.CYAN
    private val ALLY_GLASS = color(27, 61, 78, 242)
    private val ENEMY_GLASS = color(57, 36, 79, 242)
    private val VIOLET_EDGE = BattleUiTheme.CYAN
    private val MAGENTA_EDGE = BattleUiTheme.PURPLE
    private val BOOST = BattleUiTheme.GOOD
    private val DROP = BattleUiTheme.DANGER
    private val RANK_EMPTY = BattleUiTheme.BORDER
}
