package jbro.cobblemon.ui.extended

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.render.drawScaledText
import jbro.cobblemon.ui.extended.pokemon.tooltip.MoveInfo
import jbro.cobblemon.ui.extended.pokemon.tooltip.PokeballBounds
import jbro.cobblemon.ui.extended.pokemon.tooltip.TooltipBoundsData
import jbro.cobblemon.ui.extended.pokemon.tooltip.TooltipData
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component


/**
 * Dynamic, scalable Pokemon info popup with Cobblemon-style cell structure.
 *
 * Rendering layers (bottom to top):
 *   1. Borderless chamfered background
 *   2. Borderless dark cell backgrounds
 *   3. Row dividers within cells
 *   4. Text content
 *
 * The gap color (visible between cells) comes from the background.
 * Cells are drawn programmatically so they adapt to dynamic content.
 */
object PokemonInfoPopup {

    // ═══════════════════════════════════════════════════════════════
    // Layout constants (shared constants imported from UIUtils)
    // ═══════════════════════════════════════════════════════════════

    private const val POPUP_BASE_WIDTH = 240
    private const val LEFT_COL_RATIO = 0.50f
    private val LABEL_COLOR = UIUtils.color(120, 140, 170)

    // ═══════════════════════════════════════════════════════════════
    // Stat-specific colors
    // ═══════════════════════════════════════════════════════════════

    private val STAT_NAME_COLORS = mapOf(
        BattleStateTracker.BattleStat.ATTACK to TeamIndicatorUI.TOOLTIP_ATTACK,
        BattleStateTracker.BattleStat.DEFENSE to TeamIndicatorUI.TOOLTIP_DEFENSE,
        BattleStateTracker.BattleStat.SPECIAL_ATTACK to TeamIndicatorUI.TOOLTIP_SPECIAL_ATTACK,
        BattleStateTracker.BattleStat.SPECIAL_DEFENSE to TeamIndicatorUI.TOOLTIP_SPECIAL_DEFENSE,
        BattleStateTracker.BattleStat.SPEED to TeamIndicatorUI.TOOLTIP_SPEED,
        BattleStateTracker.BattleStat.ACCURACY to TeamIndicatorUI.TOOLTIP_TEXT,
        BattleStateTracker.BattleStat.EVASION to TeamIndicatorUI.TOOLTIP_TEXT
    )

    private val STATS_ORDER = listOf(
        BattleStateTracker.BattleStat.ATTACK,
        BattleStateTracker.BattleStat.DEFENSE,
        BattleStateTracker.BattleStat.SPECIAL_ATTACK,
        BattleStateTracker.BattleStat.SPECIAL_DEFENSE,
        BattleStateTracker.BattleStat.SPEED,
        BattleStateTracker.BattleStat.ACCURACY,
        BattleStateTracker.BattleStat.EVASION
    )

    // ═══════════════════════════════════════════════════════════════
    // Main render
    // ═══════════════════════════════════════════════════════════════

    internal fun render(
        context: GuiGraphics,
        bounds: PokeballBounds,
        data: TooltipData,
        screenWidth: Int,
        screenHeight: Int,
        isMinimised: Boolean
    ): TooltipBoundsData {
        val tr = Minecraft.getInstance().font
        // Whole text sizes only; the width keeps the designed proportion to the text.
        val fontScale = PixelTextLayout.crisp(TeamIndicatorUI.TOOLTIP_FONT_SCALE * PanelConfig.tooltipFontScale)
        val lineH = (TeamIndicatorUI.TOOLTIP_BASE_LINE_HEIGHT * fontScale).toInt().coerceAtLeast(7)

        val popupWidth = (POPUP_BASE_WIDTH * fontScale / TeamIndicatorUI.TOOLTIP_FONT_SCALE).toInt()
        val contentWidth = popupWidth - UIUtils.FRAME_INSET * 2
        val leftCellW = ((contentWidth - UIUtils.COL_GAP) * LEFT_COL_RATIO).toInt()
        val rightCellW = contentWidth - UIUtils.COL_GAP - leftCellW

        // ── Calculate layout ──
        val layout = calculateLayout(data, lineH)
        val totalHeight = UIUtils.FRAME_INSET + layout.headerCellH + UIUtils.CELL_GAP +
            layout.bodyCellH + layout.footerTotalH + UIUtils.FRAME_INSET

        // ── Position popup ──
        val isVertical = PanelConfig.teamIndicatorOrientation == PanelConfig.TeamIndicatorOrientation.VERTICAL
        var px: Int
        var py: Int

        if (isVertical) {
            // Vertical team layout: popup appears to the side of the pokeball
            val gap = 4
            px = if (bounds.isLeftSide) {
                bounds.x + bounds.width + gap  // Right of left-side team
            } else {
                bounds.x - popupWidth - gap    // Left of right-side team
            }
            // Vertically centered on the hovered pokeball, clamped to screen
            py = bounds.y + (bounds.height / 2) - (totalHeight / 2)
            px = px.coerceIn(4, maxOf(4, screenWidth - popupWidth - 4))
            py = py.coerceIn(4, maxOf(4, screenHeight - totalHeight - 4))
        } else {
            // Horizontal team layout: popup appears below the pokeball
            px = bounds.x + (bounds.width / 2) - (popupWidth / 2)
            py = bounds.y + bounds.height + 4
            px = px.coerceIn(4, maxOf(4, screenWidth - popupWidth - 4))
            if (py + totalHeight > screenHeight - 4) {
                py = bounds.y - totalHeight - 4
            }
            py = py.coerceAtLeast(4)
        }

        val opacity = if (isMinimised) UIUtils.MINIMISED_OPACITY else 1f

        context.pose().pushPose()
        context.pose().translate(0.0, 0.0, UIUtils.POPUP_Z_OFFSET)

        // ── Layer 1: 9-slice frame (gap-colored interior shows between cells) ──
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1f, 1f, 1f, opacity)
        UIUtils.renderPopupFrame(context, px, py, popupWidth, totalHeight)
        context.flush()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        // ── Layer 2+3+4: Cells + dividers + text ──
        val cellX = px + UIUtils.FRAME_INSET
        var curY = py + UIUtils.FRAME_INSET

        // Header cell
        drawCell(context, cellX, curY, contentWidth, layout.headerCellH)
        renderHeaderText(context, data, cellX + UIUtils.CELL_PAD, curY + UIUtils.CELL_VPAD_TOP,
            contentWidth - UIUtils.CELL_PAD * 2, lineH, fontScale, tr)
        curY += layout.headerCellH + UIUtils.CELL_GAP

        // Body cells (left + right columns)
        val leftCellX = cellX
        val rightCellX = cellX + leftCellW + UIUtils.COL_GAP
        val bodyCellH = layout.bodyCellH
        drawCell(context, leftCellX, curY, leftCellW, bodyCellH)
        drawCell(context, rightCellX, curY, rightCellW, bodyCellH)

        renderLeftCellText(context, data,
            leftCellX + UIUtils.CELL_PAD, curY + UIUtils.CELL_VPAD_TOP,
            leftCellW - UIUtils.CELL_PAD * 2,
            leftCellX, leftCellW,
            lineH, fontScale, tr)

        renderRightCellText(context, data,
            rightCellX + UIUtils.CELL_PAD, curY + UIUtils.CELL_VPAD_TOP,
            rightCellW - UIUtils.CELL_PAD * 2,
            rightCellX, rightCellW,
            lineH, fontScale, tr)

        curY += bodyCellH

        // Footer cell (volatiles)
        if (data.volatileStatuses.isNotEmpty()) {
            curY += UIUtils.CELL_GAP
            drawCell(context, cellX, curY, contentWidth, layout.footerCellH)
            renderVolatilesText(context, data,
                cellX + UIUtils.CELL_PAD, curY + UIUtils.CELL_VPAD_TOP,
                contentWidth - UIUtils.CELL_PAD * 2, lineH, fontScale, tr)
        }

        context.pose().popPose()
        return TooltipBoundsData(px, py, popupWidth, totalHeight)
    }

    // ═══════════════════════════════════════════════════════════════
    // Layout calculation
    // ═══════════════════════════════════════════════════════════════

    private data class PopupLayout(
        val headerCellH: Int,
        val bodyCellH: Int,
        val footerCellH: Int,
        val footerTotalH: Int   // UIUtils.CELL_GAP + footerCellH, or 0
    )

    private fun calculateLayout(data: TooltipData, lineH: Int): PopupLayout {
        val vPad = UIUtils.CELL_VPAD_TOP + UIUtils.CELL_VPAD_BOTTOM

        // Header: name+level, types+hp, optional tera
        val hasTera = !data.isTerastallized && PanelConfig.showTeraTypeEffective && data.teraType != null
        val headerLines = if (hasTera) 3 else 2
        val headerCellH = vPad + headerLines * lineH

        // Ability lines
        val abilityLines = when {
            data.abilityName != null -> 1
            !data.possibleAbilities.isNullOrEmpty() -> data.possibleAbilities.size
            else -> 1
        }

        // Left: BUFFS label + 7 stats (6 dividers)
        val leftContentH = lineH + 7 * lineH + 6 * UIUtils.DIVIDER_H

        // Right: ABILITY label + abilities (dividers) + gap + MOVES label + 4 moves (3 dividers)
        val abilityDividers = if (abilityLines > 1) abilityLines - 1 else 0
        val rightContentH = lineH + (abilityLines * lineH + abilityDividers * UIUtils.DIVIDER_H) +
            lineH + lineH + (4 * lineH + 3 * UIUtils.DIVIDER_H)

        val bodyContentH = maxOf(leftContentH, rightContentH)
        val bodyCellH = vPad + bodyContentH

        // Footer
        val footerCellH = if (data.volatileStatuses.isNotEmpty()) vPad + lineH else 0
        val footerTotalH = if (data.volatileStatuses.isNotEmpty()) UIUtils.CELL_GAP + footerCellH else 0

        return PopupLayout(headerCellH, bodyCellH, footerCellH, footerTotalH)
    }

    // ═══════════════════════════════════════════════════════════════
    // Cell rendering (delegates to UIUtils with opacity transform)
    // ═══════════════════════════════════════════════════════════════

    private fun drawCell(context: GuiGraphics, x: Int, y: Int, w: Int, h: Int) =
        UIUtils.drawPopupCell(context, x, y, w, h, TeamIndicatorUI::applyOpacity)

    private fun drawRowDivider(context: GuiGraphics, cellX: Int, cellW: Int, y: Int) =
        UIUtils.drawPopupRowDivider(context, cellX, cellW, y, TeamIndicatorUI::applyOpacity)

    // ═══════════════════════════════════════════════════════════════
    // Header cell text
    // ═══════════════════════════════════════════════════════════════

    private fun renderHeaderText(
        context: GuiGraphics, data: TooltipData,
        x: Int, y: Int, w: Int, lineH: Int,
        fontScale: Float, tr: net.minecraft.client.gui.Font
    ) {
        var curY = y.toFloat()

        // Line 1: Name (left) + Level (right)
        val nameColor = if (data.isTerastallized && data.activeTeraTypeName != null) {
            ElementalTypes.get(data.activeTeraTypeName.lowercase())?.let { UIUtils.getTypeColor(it) }
                ?: TeamIndicatorUI.TOOLTIP_HEADER
        } else {
            data.primaryType?.let { UIUtils.getTypeColor(it) } ?: TeamIndicatorUI.TOOLTIP_HEADER
        }
        draw(context, data.pokemonName, x.toFloat(), curY, nameColor, fontScale)

        if (data.level != null) {
            val lvlText = Component.translatable("cobblemon_ui.popup.level", data.level).string
            val lvlW = tr.width(lvlText) * fontScale
            draw(context, lvlText, x + w - lvlW, curY, TeamIndicatorUI.TOOLTIP_LABEL, fontScale)
        }
        curY += lineH

        // Line 2: Types · Item (left) | Status  HP% (right)

        // Calculate right-side positions first to know item overflow boundary
        val hpText = if (data.isKO)
            Component.translatable("cobblemon_ui.popup.ko").string
        else
            Component.translatable("cobblemon_ui.popup.hp", (data.hpPercent * 100).toInt()).string
        val hpColor = when {
            data.isKO -> UIUtils.color(100, 100, 100)
            data.hpPercent > 0.5f -> TeamIndicatorUI.TOOLTIP_HP_HIGH
            data.hpPercent > 0.25f -> TeamIndicatorUI.TOOLTIP_HP_MED
            else -> TeamIndicatorUI.TOOLTIP_HP_LOW
        }
        val hpW = tr.width(hpText) * fontScale
        val hpX = x + w - hpW
        val smallGap = tr.width("  ") * fontScale

        var rightEdge = hpX
        if (data.statusCondition != null) {
            val statusW = tr.width(TeamIndicatorUI.getStatusDisplayName(data.statusCondition)) * fontScale
            rightEdge = hpX - smallGap - statusW
        }
        val itemMaxX = rightEdge - smallGap  // leave gap before right side

        // Draw types (left)
        var typeX = x.toFloat()
        if (data.isTerastallized && data.activeTeraTypeName != null) {
            val teraPrefix = Component.translatable("cobblemon_ui.popup.tera_prefix").string
            draw(context, teraPrefix, typeX, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
            typeX += tr.width(teraPrefix) * fontScale
            val teraET = ElementalTypes.get(data.activeTeraTypeName.lowercase())
            val teraDisplayName = teraET?.displayName?.string ?: data.activeTeraTypeName
            val teraColor = teraET?.let { UIUtils.getTypeColor(it) } ?: TeamIndicatorUI.TOOLTIP_TEXT
            draw(context, teraDisplayName, typeX, curY, teraColor, fontScale)
            typeX += tr.width(teraDisplayName) * fontScale
        } else {
            val types = mutableListOf<Pair<String, Int>>()
            if (!data.lostPrimaryType) {
                data.primaryType?.let { types.add(it.displayName.string to UIUtils.getTypeColor(it)) }
            }
            data.secondaryType?.let { types.add(it.displayName.string to UIUtils.getTypeColor(it)) }
            data.addedTypes.forEach { a ->
                val et = ElementalTypes.get(a.lowercase())
                val name = et?.displayName?.string ?: a
                val color = et?.let { UIUtils.getTypeColor(it) } ?: TeamIndicatorUI.TOOLTIP_TEXT
                types.add(name to color)
            }
            if (types.isEmpty()) {
                draw(context, "???", typeX, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                typeX += tr.width("???") * fontScale
            } else {
                for ((idx, pair) in types.withIndex()) {
                    if (idx > 0) {
                        draw(context, " / ", typeX, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                        typeX += tr.width(" / ") * fontScale
                    }
                    draw(context, pair.first, typeX, curY, pair.second, fontScale)
                    typeX += tr.width(pair.first) * fontScale
                }
            }
        }

        // Draw item inline after types (only if known and fits)
        if (data.item != null) {
            val sep = " \u00b7 "
            val itemSuffix = when (data.item.status) {
                BattleStateTracker.ItemStatus.HELD -> ""
                BattleStateTracker.ItemStatus.KNOCKED_OFF -> " " + Component.translatable("cobblemon_ui.item.knocked_off").string
                BattleStateTracker.ItemStatus.STOLEN -> " " + Component.translatable("cobblemon_ui.item.stolen").string
                BattleStateTracker.ItemStatus.SWAPPED -> " " + Component.translatable("cobblemon_ui.item.swapped").string
                BattleStateTracker.ItemStatus.CONSUMED -> " " + Component.translatable("cobblemon_ui.item.consumed").string
                BattleStateTracker.ItemStatus.DESTROYED -> " " + Component.translatable("cobblemon_ui.item.destroyed").string
            }
            val itemText = data.item.name + itemSuffix
            val sepW = tr.width(sep) * fontScale
            val itemW = tr.width(itemText) * fontScale
            if (typeX + sepW + itemW <= itemMaxX) {
                draw(context, sep, typeX, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                typeX += sepW
                val itemColor = if (data.item.status == BattleStateTracker.ItemStatus.HELD)
                    TeamIndicatorUI.TOOLTIP_TEXT else TeamIndicatorUI.TOOLTIP_DIM
                draw(context, itemText, typeX, curY, itemColor, fontScale)
            }
        }

        // Draw right side: HP always far right, status to its left
        draw(context, hpText, hpX, curY, hpColor, fontScale)
        if (data.statusCondition != null) {
            val statusName = TeamIndicatorUI.getStatusDisplayName(data.statusCondition)
            val statusColor = TeamIndicatorUI.getStatusTextColor(data.statusCondition)
            val statusW = tr.width(statusName) * fontScale
            draw(context, statusName, hpX - smallGap - statusW, curY, statusColor, fontScale)
        }
        curY += lineH

        // Line 3 (optional): Tera type
        if (!data.isTerastallized && PanelConfig.showTeraTypeEffective && data.teraType != null) {
            val et = ElementalTypes.get(data.teraType.showdownId())
            val teraName = et?.displayName?.string ?: data.teraType.name
            val teraColor = et?.let { UIUtils.getTypeColor(it) } ?: TeamIndicatorUI.TOOLTIP_TEXT
            draw(context, Component.translatable("cobblemon_ui.popup.tera_label", teraName).string, x.toFloat(), curY, teraColor, fontScale)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Left cell text: Stats
    // ═══════════════════════════════════════════════════════════════

    private fun renderLeftCellText(
        context: GuiGraphics, data: TooltipData,
        x: Int, y: Int, w: Int,
        cellX: Int, cellW: Int,
        lineH: Int, fontScale: Float, tr: net.minecraft.client.gui.Font
    ) {
        var curY = y.toFloat()
        val xf = x.toFloat()

        // Section label: BUFFS/DEBUFFS
        drawLabel(context,
            Component.translatable("cobblemon_ui.popup.buffs").string,
            xf, curY, fontScale)
        curY += lineH

        // 7 stat rows with dividers between them
        for ((i, stat) in STATS_ORDER.withIndex()) {
            if (i > 0) {
                curY += 2f
                drawRowDivider(context, cellX, cellW, curY.toInt())
                curY += (UIUtils.DIVIDER_H - 2).toFloat()
            }

            val stage = data.statChanges[stat] ?: 0
            val nameColor = STAT_NAME_COLORS[stat] ?: TeamIndicatorUI.TOOLTIP_TEXT
            draw(context, stat.displayName, xf, curY, nameColor, fontScale)

            // Stage (right-aligned)
            val stageText = formatStage(stage)
            val stageW = tr.width(stageText) * fontScale
            draw(context, stageText, x + w - stageW, curY, getStageColor(stage), fontScale)

            // Stat values between name and stage
            val gap = tr.width("  ") * fontScale
            when {
                // Player Pokemon: show actual → effective for all stats
                data.isPlayerPokemon && PanelConfig.showStatRangesEffective -> {
                    val actualValue = getActualStat(data, stat)
                    if (actualValue != null) {
                        val effective = calculateEffectiveStat(actualValue, stage)
                        if (effective != actualValue) {
                            val effectiveText = "$effective"
                            val effectiveW = tr.width(effectiveText) * fontScale
                            val effectiveColor = if (effective > actualValue)
                                TeamIndicatorUI.TOOLTIP_STAT_BOOST else TeamIndicatorUI.TOOLTIP_STAT_DROP
                            draw(context, effectiveText, x + w - stageW - gap - effectiveW, curY,
                                effectiveColor, fontScale)

                            val arrowText = " \u2192 "
                            val arrowW = tr.width(arrowText) * fontScale
                            val baseText = "$actualValue"
                            val baseW = tr.width(baseText) * fontScale
                            draw(context, arrowText, x + w - stageW - gap - effectiveW - arrowW, curY,
                                TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                            draw(context, baseText, x + w - stageW - gap - effectiveW - arrowW - baseW, curY,
                                TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                        } else {
                            val valText = "$actualValue"
                            val valW = tr.width(valText) * fontScale
                            draw(context, valText, x + w - stageW - gap - valW, curY,
                                TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                        }
                    }
                }
                // Opponent Pokemon: optional speed range inline (label/stage always drawn above)
                !data.isPlayerPokemon && stat == BattleStateTracker.BattleStat.SPEED
                    && data.pokemonId != null && data.level != null
                    && PanelConfig.showOpponentSpeedRangeEffective -> {
                    val range = TeamIndicatorUI.calculateOpponentSpeedRange(
                        data.uuid, data.pokemonId, data.level, stage,
                        data.statusCondition, data.item, data.form
                    )
                    if (range != null) {
                        val rangeText = "${range.minSpeed}-${range.maxSpeed}"
                        val rangeW = tr.width(rangeText) * fontScale
                        draw(context, rangeText, x + w - stageW - gap - rangeW, curY,
                            TeamIndicatorUI.TOOLTIP_SPEED, fontScale)
                    }
                }
            }

            curY += lineH
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Right cell text: Ability + Moves
    // ═══════════════════════════════════════════════════════════════

    private fun renderRightCellText(
        context: GuiGraphics, data: TooltipData,
        x: Int, y: Int, w: Int,
        cellX: Int, cellW: Int,
        lineH: Int, fontScale: Float,
        tr: net.minecraft.client.gui.Font
    ) {
        var curY = y.toFloat()
        val xf = x.toFloat()

        // Section label: ABILITY
        drawLabel(context,
            Component.translatable("cobblemon_ui.popup.ability").string,
            xf, curY, fontScale)
        curY += lineH

        // Ability lines
        when {
            data.abilityName != null -> {
                draw(context, data.abilityName, xf, curY,
                    TeamIndicatorUI.TOOLTIP_ABILITY, fontScale)
                curY += lineH
            }
            !data.possibleAbilities.isNullOrEmpty() -> {
                for ((i, ability) in data.possibleAbilities.withIndex()) {
                    if (i > 0) {
                        curY += 2f
                        drawRowDivider(context, cellX, cellW, curY.toInt())
                        curY += (UIUtils.DIVIDER_H - 2).toFloat()
                    }
                    draw(context, ability, xf, curY,
                        TeamIndicatorUI.TOOLTIP_ABILITY_POSSIBLE, fontScale)
                    curY += lineH
                }
            }
            else -> {
                draw(context, "?", xf, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                curY += lineH
            }
        }

        // Gap
        curY += lineH

        // Section label: MOVES
        drawLabel(context,
            Component.translatable("cobblemon_ui.popup.moves").string,
            xf, curY, fontScale)
        curY += lineH

        // 4 move slots with dividers
        val moves = data.moves.take(4)
        for (i in 0 until 4) {
            if (i > 0) {
                curY += 2f
                drawRowDivider(context, cellX, cellW, curY.toInt())
                curY += (UIUtils.DIVIDER_H - 2).toFloat()
            }

            if (i < moves.size) {
                val move = moves[i]
                val normalized = move.id
                    ?: move.name.lowercase().replace(" ", "").replace("-", "")
                val template = Moves.getByName(normalized)
                    ?: Moves.getByName(move.name.lowercase())
                val moveColor = template?.let { UIUtils.getTypeColor(it.elementalType) }
                    ?: TeamIndicatorUI.TOOLTIP_TEXT

                draw(context, move.name, xf, curY, moveColor, fontScale)

                val ppText = buildPpText(move, data.isPlayerPokemon)
                if (ppText != null) {
                    val ppW = tr.width(ppText) * fontScale
                    draw(context, ppText, x + w - ppW, curY,
                        getPpColor(move, data.isPlayerPokemon), fontScale)
                }
            } else if (!data.isPlayerPokemon) {
                draw(context, "???", xf, curY, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
            }
            curY += lineH
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Footer cell text: Volatiles
    // ═══════════════════════════════════════════════════════════════

    private fun renderVolatilesText(
        context: GuiGraphics, data: TooltipData,
        x: Int, y: Int, w: Int, lineH: Int,
        fontScale: Float, tr: net.minecraft.client.gui.Font
    ) {
        val label = Component.translatable("cobblemon_ui.popup.volatiles").string + ": "
        var curX = x.toFloat()
        val yf = y.toFloat()

        draw(context, label, curX, yf, TeamIndicatorUI.TOOLTIP_LABEL, fontScale)
        curX += tr.width(label) * fontScale

        val maxX = x + w
        data.volatileStatuses.toList().forEachIndexed { i, volatile ->
            if (i > 0) {
                draw(context, ", ", curX, yf, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                curX += tr.width(", ") * fontScale
            }
            val name = volatile.type.displayName
            val nameW = tr.width(name) * fontScale
            if (curX + nameW > maxX) {
                draw(context, "...", curX, yf, TeamIndicatorUI.TOOLTIP_DIM, fontScale)
                return
            }
            val color = if (volatile.type.isNegative)
                TeamIndicatorUI.TOOLTIP_STAT_DROP else TeamIndicatorUI.TOOLTIP_STAT_BOOST
            draw(context, name, curX, yf, color, fontScale)
            curX += nameW
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun getActualStat(
        data: TooltipData,
        stat: BattleStateTracker.BattleStat
    ): Int? = when (stat) {
        BattleStateTracker.BattleStat.ATTACK -> data.actualAttack
        BattleStateTracker.BattleStat.DEFENSE -> data.actualDefence
        BattleStateTracker.BattleStat.SPECIAL_ATTACK -> data.actualSpecialAttack
        BattleStateTracker.BattleStat.SPECIAL_DEFENSE -> data.actualSpecialDefence
        BattleStateTracker.BattleStat.SPEED -> data.actualSpeed
        else -> null
    }

    private fun calculateEffectiveStat(baseStat: Int, stage: Int): Int {
        // Only apply the authoritative battle stage here. Ability/item effects are not complete
        // enough to present as an exact in-battle stat.
        return (baseStat * TeamIndicatorUI.getStageMultiplier(stage)).toInt()
    }

    private fun formatStage(stage: Int): String = when {
        stage > 0 -> "+$stage"
        stage < 0 -> "$stage"
        else -> "+0"
    }

    private fun getStageColor(stage: Int): Int = when {
        stage > 0 -> TeamIndicatorUI.TOOLTIP_STAT_BOOST
        stage < 0 -> TeamIndicatorUI.TOOLTIP_STAT_DROP
        else -> TeamIndicatorUI.TOOLTIP_DIM
    }

    private fun buildPpText(move: MoveInfo, isPlayer: Boolean): String? =
        if (isPlayer && move.currentPp != null && move.maxPp != null) "(${move.currentPp}/${move.maxPp})" else null

    private fun getPpColor(move: MoveInfo, isPlayer: Boolean): Int {
        val ratio = when {
            isPlayer && move.currentPp != null && move.maxPp != null ->
                move.currentPp.toFloat() / move.maxPp.coerceAtLeast(1)
            else -> 1f
        }
        return if (ratio <= 0.25f) TeamIndicatorUI.TOOLTIP_PP_LOW else TeamIndicatorUI.TOOLTIP_PP
    }

    // ═══════════════════════════════════════════════════════════════
    // Text drawing
    // ═══════════════════════════════════════════════════════════════

    private fun drawLabel(
        context: GuiGraphics, text: String,
        x: Float, y: Float, fontScale: Float
    ) {
        val labelScale = fontScale * 0.9f
        drawScaledText(
            context = context,
            text = Component.literal(text.uppercase()),
            x = x, y = y,
            scale = labelScale,
            colour = TeamIndicatorUI.applyOpacity(jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme.readable(LABEL_COLOR)),
            shadow = false
        )
    }

    private fun draw(
        context: GuiGraphics, text: String,
        x: Float, y: Float, color: Int, fontScale: Float
    ) {
        drawScaledText(
            context = context,
            text = Component.literal(text),
            x = x, y = y,
            scale = fontScale,
            colour = TeamIndicatorUI.applyOpacity(jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme.readable(color)),
            shadow = false
        )
    }
}
