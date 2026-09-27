package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.types.ElementalTypes
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Non-interactive, in-world design pages. The capture fixture is its only caller. */
internal object BattleScreenDraft {
    private const val CARD_W = 184
    private const val CARD_H = 50
    private const val COMPACT_CARD_W = 156

    private data class SamplePokemon(val species: String, val hp: Float, val level: Int = 50,
                                     val fainted: Boolean = false, val burned: Boolean = false)

    fun render(context: DrawContext, page: String, width: Int, height: Int) {
        val korean = MinecraftClient.getInstance().options.language == "ko_kr"
        context.fill(0, 0, width, height, 0x4906101E)
        drawCaption(context, page, width, korean)
        val count = when (page) {
            "draft-hud-double", "draft-target" -> 2
            "draft-hud-triple", "draft-target-triple" -> 3
            else -> 1
        }
        if (count > 1) {
            val allies = listOf(SamplePokemon("pikachu", .72f), SamplePokemon("bulbasaur", .91f), SamplePokemon("eevee", .48f))
            val opponents = listOf(SamplePokemon("charizard", .36f), SamplePokemon("venusaur", .54f, burned = true),
                SamplePokemon("blastoise", .67f))
            repeat(count) { index ->
                drawHud(context, 10, 22 + index * 33, allies[index], true, korean, compact = true)
                drawHud(context, width - COMPACT_CARD_W - 10, 22 + index * 33, opponents[index], false, korean, compact = true)
            }
            if (page == "draft-target" || page == "draft-target-triple")
                drawTarget(context, width, height, korean, count)
            return
        }
        drawHud(context, 10, 28, SamplePokemon("pikachu", .72f), true, korean)
        drawHud(context, width - CARD_W - 10, 28, SamplePokemon("charizard", .36f, burned = true), false, korean)
        when (page) {
            "draft-menu-rail" -> drawCommands(context, width, height, true)
            "draft-menu-blade" -> drawCommands(context, width, height, false)
            "draft-moves" -> drawMoves(context, width, height)
            "draft-switch" -> drawSwitch(context, width, height)
            "draft-forfeit" -> drawForfeit(context, width, height, korean)
        }
    }

    private fun drawCaption(context: DrawContext, page: String, width: Int, korean: Boolean) {
        val title = if (korean) "전투 UI 초안 · 입력 없음" else "BATTLE UI DRAFT · NO INPUT"
        text(context, title, 10, 8, BattleUiTheme.TEXT)
        val suffix = page.removePrefix("draft-").uppercase()
        rightText(context, suffix, width - 10, 8, BattleUiTheme.CYAN)
    }

    private fun drawHud(context: DrawContext, x: Int, y: Int, sample: SamplePokemon, self: Boolean, korean: Boolean,
                        compact: Boolean = false) {
        val accent = if (self) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        val corners = if (self) 0b1001 else 0b0110
        val cardWidth = if (compact) COMPACT_CARD_W else CARD_W
        val height = if (compact) 30 else CARD_H
        val directionalCuts = if (self) BattleCornerCuts(topLeft = 3, bottomRight = 10)
            else BattleCornerCuts(topRight = 3, bottomLeft = 10)
        BattleSurfaceRenderer.draw(context, x, y, cardWidth, height,
            BattleUiTheme.panel.copy(top = 0xE01A3045.toInt(), bottom = 0xDC0D1A2B.toInt(), border = accent,
                cut = 5, corners = corners, cornerCuts = directionalCuts))
        val portraitSize = if (compact) 22 else 30
        val portraitX = if (self) x + 7 else x + cardWidth - portraitSize - 7
        val portraitY = y + if (compact) 3 else 6
        BattleSurfaceRenderer.draw(context, portraitX, portraitY, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(border = accent, cut = 4, corners = corners))
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 2, portraitY + 2, portraitSize - 4, null,
            Identifier.of("cobblemon", sample.species), emptySet(),
            UUID.nameUUIDFromBytes(sample.species.toByteArray()), sample.fainted, null, self, { it }, 1f)

        val contentX = if (self) x + portraitSize + 13 else x + 9
        val contentRight = if (self) x + cardWidth - 10 else x + cardWidth - portraitSize - 14
        val name = Text.translatable("cobblemon.species.${sample.species}.name").string
        val font = MinecraftClient.getInstance().textRenderer
        if (compact) {
            text(context, font.trimToWidth(name, contentRight - contentX - 26), contentX, y + 3, BattleUiTheme.TEXT)
            rightText(context, "${sample.level}", contentRight, y + 3, BattleUiTheme.MUTED)
            val barRight = minOf(contentX + 60, contentRight - if (self) 43 else 31)
            context.fill(contentX, y + 15, barRight, y + 19, BattleUiTheme.TRACK)
            context.fill(contentX + 1, y + 16,
                contentX + 1 + ((barRight - contentX - 2) * sample.hp).toInt(), y + 18, hpColor(sample.hp))
            rightText(context, if (self) "${(sample.hp * 120).toInt()}/120" else "${(sample.hp * 100).toInt()}%",
                contentRight, y + 14, BattleUiTheme.TEXT)
            if (sample.burned) {
                val label = if (korean) "화상" else "BRN"
                context.fill(contentX, y + 19, contentX + font.getWidth(label) + 4, y + 28,
                    BattleStatusPalette.background(Statuses.BURN.showdownName))
                text(context, label, contentX + 2, y + 19, 0xFF182337.toInt())
            }
            return
        }
        val role = if (self) (if (korean) "내 포켓몬" else "YOUR POKÉMON")
            else (if (korean) "상대 포켓몬" else "OPPONENT")
        text(context, role, contentX, y + 4, accent)
        if (sample.burned) {
            val label = if (korean) "화상" else "BRN"
            val badgeX = contentRight - font.getWidth(label) - 4
            context.fill(badgeX, y + 3, contentRight, y + 12,
                BattleStatusPalette.background(Statuses.BURN.showdownName))
            text(context, label, badgeX + 2, y + 3, 0xFF182337.toInt())
        }
        text(context, font.trimToWidth(name, contentRight - contentX - 37), contentX, y + 15, BattleUiTheme.TEXT)
        rightText(context, "${sample.level}", contentRight, y + 15, BattleUiTheme.MUTED)
        val barY = y + 31
        context.fill(contentX, barY, contentRight, barY + 6, BattleUiTheme.TRACK)
        context.fill(contentX + 1, barY + 1,
            contentX + 1 + ((contentRight - contentX - 2) * sample.hp).toInt(), barY + 5, hpColor(sample.hp))
        text(context, if (self) "HP" else "HP ${ (sample.hp * 100).toInt() }%", contentX, y + 40, BattleUiTheme.MUTED)
        if (self) rightText(context, "${(sample.hp * 120).toInt()}/120", contentRight, y + 40, BattleUiTheme.TEXT)
    }

    private fun drawCommands(context: DrawContext, width: Int, height: Int, rail: Boolean) {
        val labels = listOf("fight", "switch", "capture", "run")
        val x = width - 102
        val top = height - 126
        labels.forEachIndexed { index, key ->
            val y = top + index * 30
            val style = when {
                index == 0 && rail -> BattleUiTheme.primary.copy(cut = 6, corners = 0b1001)
                index == 0 -> BattleUiTheme.primary.copy(cut = 6, corners = 0b1001)
                index == 3 -> BattleUiTheme.danger.copy(backgroundOpacity = 1f,
                    borderWidth = if (rail) 0 else 1, cut = 5, corners = 0b0101)
                index == 2 -> BattleUiTheme.capture.copy(backgroundOpacity = 1f,
                    borderWidth = if (rail) 0 else 1, cut = 4, corners = 0b1010)
                else -> BattleUiTheme.secondary.copy(backgroundOpacity = 1f,
                    borderWidth = if (rail) 0 else 1, cut = if (rail) 4 else 0)
            }
            val label = Text.translatable("cobblemon.battle.ui.$key")
            if (rail) {
                val base = when (index) {
                    0 -> BattleUiTheme.primary
                    2 -> BattleUiTheme.capture
                    3 -> BattleUiTheme.danger
                    else -> BattleUiTheme.secondary
                }
                BattleControlRenderer.drawOption(context, x, y, label, base, index == 0, false)
            } else {
                BattleSurfaceRenderer.draw(context, x, y, 90, 26, style)
                centerText(context, label.string, x + 45, y + 9,
                    if (index == 0) BattleUiTheme.PANEL_ALT else BattleUiTheme.TEXT)
            }
        }
    }

    private fun drawMoves(context: DrawContext, width: Int, height: Int) {
        val bounds = BattleScreenGeometry.moveTiles(width, height, 4)
        val moves = listOf("thunderbolt", "quickattack", "protect", "irontail")
        val types = listOf("electric", "normal", "normal", "steel")
        val colors = listOf(0xFFF3D03E.toInt(), 0xFFA8A878.toInt(), 0xFFA8A878.toInt(), 0xFFB8B8D0.toInt())
        moves.forEachIndexed { index, key ->
            val x = bounds[index].x()
            val y = bounds[index].y()
            val active = index == 1
            val disabled = index == 3
            val move = requireNotNull(Moves.getByName(key))
            BattleControlRenderer.drawMove(context, x.toFloat(), y.toFloat(), move,
                ElementalTypes.get(types[index])!!, colors[index], if (disabled) 0 else 10, 15,
                !disabled, active)
        }
    }

    private fun drawSwitch(context: DrawContext, width: Int, height: Int) {
        val samples = listOf(
            SamplePokemon("pikachu", .72f), SamplePokemon("bulbasaur", .91f),
            SamplePokemon("eevee", .48f), SamplePokemon("squirtle", .67f),
            SamplePokemon("charmander", .21f, burned = true), SamplePokemon("jigglypuff", 0f, fainted = true)
        )
        val cards = samples.mapIndexed { index, sample ->
            BattlePartyCard(
                Identifier.of("cobblemon", sample.species), emptySet(),
                UUID.nameUUIDFromBytes(sample.species.toByteArray()),
                Text.translatable("cobblemon.species.${sample.species}.name"), sample.level,
                sample.hp, sample.fainted, index == 0,
                if (sample.burned) Statuses.BURN else null,
                if (sample.burned) Text.translatable("cobblemon_battle_ui.switch.status.brn") else null
            )
        }
        BattleSwitchRenderer.draw(context, width, height, cards, focused = 2)
    }

    /** Functionless target-page proposal, deliberately separate from Cobblemon's input path. */
    private fun drawTarget(context: DrawContext, width: Int, height: Int, korean: Boolean, slots: Int) {
        val panel = BattleScreenGeometry.targetPanel(width, height, slots)
        // Targeting needs labels and focus, not a second HP dashboard or an enclosing shell.
        text(context, if (korean) "대상 선택" else "SELECT TARGET", panel.x() + 6, panel.y() + 2,
            BattleUiTheme.TEXT)
        val back = BattleScreenGeometry.targetBack(width, height, slots)
        BattleSurfaceRenderer.draw(context, back.x(), back.y(), back.width(), back.height(),
            BattleUiTheme.secondary.copy(border = BattleUiTheme.CYAN, cut = 3, corners = 0b1010))
        centerText(context, if (korean) "뒤로" else "BACK", back.x() + back.width() / 2,
            back.y() + 2, BattleUiTheme.TEXT)
        text(context, if (korean) "아군" else "ALLY", panel.x() + 6,
            panel.y() + 12, BattleUiTheme.TEXT)
        text(context, if (korean) "상대" else "OPPONENT",
            BattleScreenGeometry.targetTile(width, height, slots, 1, 0).x(),
            panel.y() + 12, BattleUiTheme.TEXT)
        val opponents = if (slots == 2) listOf("squirtle", "charizard")
            else listOf("squirtle", "charizard", "meowth")
        val allies = if (slots == 2) listOf("pikachu", "bulbasaur")
            else listOf("pikachu", "bulbasaur", "eevee")
        allies.forEachIndexed { column, species ->
            drawTargetCard(context, BattleScreenGeometry.targetTile(width, height, slots, 0, column),
                species, true, false, column == 0)
        }
        opponents.forEachIndexed { column, species ->
            drawTargetCard(context, BattleScreenGeometry.targetTile(width, height, slots, 1, column),
                species, false, column == 1, false)
        }
    }

    private fun drawTargetCard(context: DrawContext, rect: jbro.cobblemon.battleui.navigation.UiRect,
                               species: String, ally: Boolean, focused: Boolean, unavailable: Boolean) {
        val x = rect.x()
        val y = rect.y()
        val portraitSize = 16
        val portraitX = x + rect.width() - portraitSize - 4
        val accent = if (focused) BattleUiTheme.FOCUS else if (ally) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        BattleSurfaceRenderer.draw(context, x, y, rect.width(), rect.height(),
            BattleUiTheme.panel.copy(border = accent, borderWidth = if (focused) 2 else 1,
                cut = 3, corners = 0b1001), if (unavailable) .7f else 1f)
        val name = Text.translatable("cobblemon.species.$species.name").string
        val font = MinecraftClient.getInstance().textRenderer
        text(context, font.trimToWidth(name, portraitX - x - if (unavailable) 20 else 10),
            x + if (unavailable) 15 else 5, y + 7,
            if (unavailable) BattleUiTheme.MUTED else BattleUiTheme.TEXT)
        if (unavailable) text(context, "×", x + 5, y + 7, BattleUiTheme.DANGER)
        BattleSurfaceRenderer.draw(context, portraitX, y + 3, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(border = accent, cut = 3))
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 1, y + 4,
            portraitSize - 2, null,
            Identifier.of("cobblemon", species), emptySet(), UUID.nameUUIDFromBytes(species.toByteArray()),
            false, null, ally, { it }, 1f)
    }

    private fun hpColor(hp: Float) = when {
        hp > .5f -> BattleUiTheme.GOOD
        hp > .25f -> BattleUiTheme.FOCUS
        else -> BattleUiTheme.DANGER
    }

    private fun drawForfeit(context: DrawContext, width: Int, height: Int, korean: Boolean) {
        BattleForfeitRenderer.draw(context, width, height,
            Text.literal(if (korean) "전투를 포기하시겠습니까?" else "FORFEIT THIS BATTLE?"),
            Text.literal(if (korean) "선택하면 이 전투에서 패배합니다." else "This counts as a loss."),
            Text.literal(if (korean) "포기" else "FORFEIT"),
            Text.literal(if (korean) "돌아가기" else "BACK"))
    }

    private fun text(context: DrawContext, value: String, x: Int, y: Int, color: Int) =
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y, color, false)

    private fun rightText(context: DrawContext, value: String, right: Int, y: Int, color: Int) {
        text(context, value, right - MinecraftClient.getInstance().textRenderer.getWidth(value), y, color)
    }

    private fun centerText(context: DrawContext, value: String, center: Int, y: Int, color: Int) {
        text(context, value, center - MinecraftClient.getInstance().textRenderer.getWidth(value) / 2, y, color)
    }
}
