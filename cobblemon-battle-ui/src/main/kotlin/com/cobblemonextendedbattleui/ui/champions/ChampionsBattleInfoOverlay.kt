package jbro.cobblemon.battleui.extended.ui.champions

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import jbro.cobblemon.battleui.extended.BattleStateTracker
import jbro.cobblemon.battleui.extended.TeamIndicatorUI
import jbro.cobblemon.battleui.extended.UIUtils
import jbro.cobblemon.battleui.extended.ui.shared.BattleUiDesignTokens as Ui
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.UUID
import kotlin.math.min

/**
 * Large battle-status modal. Only currently active Pokemon and battlefield
 * conditions are shown; team intel, moves, items, abilities and models are
 * intentionally excluded from this screen.
 */
object ChampionsBattleInfoOverlay {
    private const val BASE_W = 840
    private const val BASE_H = 440
    private const val SIDE_X_LEFT = 18
    private const val FIELD_X = 280
    private const val SIDE_X_RIGHT = 570
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
    private var effects: List<EffectRow> = emptyList()

    private data class PokemonEntry(
        val uuid: UUID,
        val name: String,
        val hpPercent: Float,
        val status: Status?,
        val level: Int?
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
        effects = emptyList()
    }

    fun onOpened() = Unit

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
        val originY = (screenHeight - BASE_H * scale) / 2f

        context.fill(0, 0, screenWidth, screenHeight, Ui.SCRIM)
        context.matrices.push()
        context.matrices.translate(originX.toDouble(), originY.toDouble(), UIUtils.MODAL_Z_OFFSET)
        context.matrices.scale(scale, scale, 1f)
        drawOverlay(context)
        context.matrices.pop()
    }

    private fun drawOverlay(context: DrawContext) {
        drawFrame(context)
        drawSidePanel(context, activeAllies, SIDE_X_LEFT, false)
        drawEffectsPanel(context, FIELD_X)
        drawSidePanel(context, activeOpponents, SIDE_X_RIGHT, true)
        drawTextCentered(context, tr("cobblemon_battle_ui.champions.close"), BASE_W / 2, 417, Ui.TEXT_SECONDARY, 0.95f)
    }

    private fun drawFrame(context: DrawContext) {
        context.fill(8, 12, BASE_W - 8, BASE_H - 8, Ui.SHELL)
        context.fill(9, 13, BASE_W - 9, 15, Ui.ACCENT_PRIMARY)
        context.fill(9, BASE_H - 11, BASE_W - 9, BASE_H - 9, Ui.ACCENT_SECONDARY)
        context.fill(346, 10, 494, 51, Ui.HEADER)
        drawBorder(context, 346, 10, 148, 41, Ui.BORDER_BRIGHT, 2)
        drawTextCentered(context, tr("cobblemon_battle_ui.champions.title"), BASE_W / 2, 24, Ui.TEXT_PRIMARY, 1.2f)
    }

    private fun drawSidePanel(
        context: DrawContext,
        entries: List<PokemonEntry>,
        x: Int,
        opponent: Boolean
    ) {
        val accent = if (opponent) Ui.ACCENT_DANGER else Ui.ACCENT_PRIMARY
        val title = tr(
            if (opponent) "cobblemon_battle_ui.champions.opponent_active"
            else "cobblemon_battle_ui.champions.your_active"
        )

        context.fill(x, PANEL_Y, x + SIDE_W, PANEL_Y + PANEL_H, Ui.PANEL)
        context.fill(x, PANEL_Y, x + SIDE_W, PANEL_Y + 38, Ui.HEADER)
        context.fill(x, PANEL_Y, x + 5, PANEL_Y + PANEL_H, accent)
        drawBorder(context, x, PANEL_Y, SIDE_W, PANEL_H, Ui.BORDER, 1)
        drawTextCentered(context, title, x + SIDE_W / 2, PANEL_Y + 14, Ui.TEXT_PRIMARY, 1.15f)

        if (entries.isEmpty()) {
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.champions.no_active"),
                x + SIDE_W / 2,
                PANEL_Y + 180,
                Ui.TEXT_DIM,
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
            drawPokemonCard(context, entry, x + 10, cardTop + index * (cardHeight + gap), SIDE_W - 20, cardHeight, accent)
        }
    }

    private fun drawPokemonCard(
        context: DrawContext,
        entry: PokemonEntry,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        accent: Int
    ) {
        context.fill(x, y, x + width, y + height, Ui.PANEL_ALT)
        context.fill(x, y, x + 3, y + height, accent)
        drawBorder(context, x, y, width, height, Ui.BORDER, 1)

        val nameWidth = if (entry.level == null) width - 28 else width - 84
        drawText(context, trim(entry.name, nameWidth), x + 14, y + 13, Ui.TEXT_PRIMARY, if (height >= 200) 1.2f else 1.05f)
        entry.level?.let { drawTextRight(context, "Lv.$it", x + width - 14, y + 15, Ui.TEXT_DIM, 0.95f) }

        val status = entry.status?.let { TeamIndicatorUI.getStatusDisplayName(it) }
            ?: tr("cobblemon_battle_ui.champions.normal")
        drawText(context, tr("cobblemon_battle_ui.champions.status"), x + 14, y + 42, Ui.TEXT_SECONDARY, 0.9f)
        drawText(context, status, x + 68, y + 42, Ui.TEXT_PRIMARY, 1.0f)
        drawHpBar(context, x + 14, y + 64, width - 28, entry.hpPercent, height >= 200)

        if (height >= 200) {
            drawDetailedConditions(context, entry, x, y, width)
        } else if (height >= COMPACT_CONDITIONS_MIN_HEIGHT) {
            drawCompactConditions(context, entry, x, y, width)
        }
    }

    private fun drawDetailedConditions(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        val volatileText = volatileText(entry.uuid, width - 28)
        drawText(context, tr("cobblemon_battle_ui.champions.additional_status"), x + 14, y + 103, Ui.TEXT_SECONDARY, 0.9f)
        drawText(context, volatileText, x + 14, y + 124, Ui.TEXT_PRIMARY, 1.0f)
        drawText(context, tr("cobblemon_battle_ui.champions.stat_changes"), x + 14, y + 156, Ui.TEXT_SECONDARY, 0.9f)
        drawDetailedRankRows(context, entry.uuid, x + 14, y + 178, width - 28)
    }

    private fun drawCompactConditions(context: DrawContext, entry: PokemonEntry, x: Int, y: Int, width: Int) {
        drawCompactRankGrid(context, entry.uuid, x + 14, y + 86, width - 28)
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
            val rowY = y + index * 15
            drawText(context, stat.displayName, x, rowY, Ui.TEXT_PRIMARY, 0.9f)
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
            val rowY = y + row * 13
            drawText(context, stat.abbr, cellX, rowY, Ui.TEXT_PRIMARY, 0.85f)
            drawRankTrack(context, cellX + 25, rowY + 2, stage, 5, 5, 2)
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
            val cellColor = if (index < activeCells) stageColor(stage) else Ui.BORDER
            context.fill(cellX, y, cellX + cellWidth, y + cellHeight, cellColor)
        }
    }

    private fun drawEffectsPanel(context: DrawContext, x: Int) {
        context.fill(x, PANEL_Y, x + FIELD_W, PANEL_Y + PANEL_H, Ui.PANEL)
        context.fill(x, PANEL_Y, x + FIELD_W, PANEL_Y + 38, Ui.HEADER)
        drawBorder(context, x, PANEL_Y, FIELD_W, PANEL_H, Ui.BORDER, 1)
        drawTextCentered(
            context,
            tr("cobblemon_battle_ui.champions.effects"),
            x + FIELD_W / 2,
            PANEL_Y + 14,
            Ui.TEXT_PRIMARY,
            1.1f
        )

        if (effects.isEmpty()) {
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.ui.no_effects"),
                x + FIELD_W / 2,
                PANEL_Y + 180,
                Ui.TEXT_DIM,
                1.05f
            )
            return
        }

        val effectWindow = EffectListLayout.window(effects.size)
        effects.take(effectWindow.visibleEffectCount).forEachIndexed { index, effect ->
            val rowY = PANEL_Y + 48 + index * 36
            context.fill(x + 10, rowY, x + FIELD_W - 10, rowY + 31, Ui.PANEL_ALT)
            context.fill(x + 10, rowY, x + 13, rowY + 31, if (effect.opponent) Ui.ACCENT_DANGER else Ui.ACCENT_PRIMARY)
            drawText(context, effect.group, x + 21, rowY + 6, Ui.TEXT_SECONDARY, 0.85f)
            drawText(context, trim(effect.name, 142), x + 88, rowY + 6, Ui.TEXT_PRIMARY, 1.0f)
            effect.turns?.let { drawTextRight(context, it, x + FIELD_W - 19, rowY + 7, Ui.TEXT_PRIMARY, 0.9f) }
        }

        if (effectWindow.hiddenEffectCount > 0) {
            val rowY = PANEL_Y + 48 + effectWindow.visibleEffectCount * 36
            context.fill(x + 10, rowY, x + FIELD_W - 10, rowY + 31, Ui.PANEL_ALT)
            context.fill(x + 10, rowY, x + 13, rowY + 31, Ui.ACCENT_PRIMARY)
            drawTextCentered(
                context,
                tr("cobblemon_battle_ui.champions.more_effects", effectWindow.hiddenEffectCount),
                x + FIELD_W / 2,
                rowY + 7,
                Ui.TEXT_DIM,
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
        detailed: Boolean
    ) {
        val height = if (detailed) 12 else 9
        context.fill(x, y, x + width, y + height, Ui.TRACK)
        val clamped = hpPercent.coerceIn(0f, 1f)
        val hpColor = when {
            clamped > .5f -> Ui.ACCENT_GOOD
            clamped > .25f -> Ui.ACCENT_CAUTION
            else -> Ui.ACCENT_DANGER
        }
        val fillWidth = ((width - 4) * clamped).toInt()
        context.fill(x + 2, y + 2, x + 2 + fillWidth, y + height - 2, hpColor)
        drawTextRight(context, "${(clamped * 100).toInt()}%", x + width, y + height + 4, Ui.TEXT_PRIMARY, 0.9f)
    }

    private fun volatileText(uuid: UUID, width: Int): String {
        val statuses = BattleStateTracker.getVolatileStatuses(uuid)
            .map { state -> state.type.displayName }
        val text = if (statuses.isEmpty()) {
            tr("cobblemon_battle_ui.champions.no_additional_status")
        } else {
            statuses.joinToString(" · ")
        }
        return trim(text, width)
    }

    private fun stageText(stage: Int): String = if (stage > 0) "+$stage" else stage.toString()

    private fun stageColor(stage: Int): Int = when {
        stage > 0 -> Ui.ACCENT_GOOD
        stage < 0 -> Ui.ACCENT_PRIMARY
        else -> Ui.TEXT_DIM
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
            level = pokemon.properties.level
        )
    }

    private fun turnText(turns: String?): String? = turns?.let { tr("cobblemon_battle_ui.champions.turns", it) }
    private fun tr(key: String, vararg args: Any): String = Text.translatable(key, *args).string
    private fun trim(text: String, width: Int): String = MinecraftClient.getInstance().textRenderer.trimToWidth(text, width)

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int, textScale: Float) =
        UIUtils.drawText(context, text, x.toFloat(), y.toFloat(), color, textScale)

    private fun drawTextRight(context: DrawContext, text: String, right: Int, y: Int, color: Int, textScale: Float) {
        val width = MinecraftClient.getInstance().textRenderer.getWidth(text) * textScale
        UIUtils.drawText(context, text, right - width, y.toFloat(), color, textScale)
    }

    private fun drawTextCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int, textScale: Float) {
        val width = MinecraftClient.getInstance().textRenderer.getWidth(text) * textScale
        UIUtils.drawText(context, text, centerX - width / 2f, y.toFloat(), color, textScale)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, width: Int, height: Int, color: Int, thickness: Int) {
        context.fill(x, y, x + width, y + thickness, color)
        context.fill(x, y + height - thickness, x + width, y + height, color)
        context.fill(x, y, x + thickness, y + height, color)
        context.fill(x + width - thickness, y, x + width, y + height, color)
    }

}
