package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.pokemon.Gender
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Non-interactive, in-world design pages. The capture fixture is its only caller. */
internal object BattleScreenDraft {
    private const val CARD_W = 156
    private const val CARD_H = 44
    private const val COMPACT_CARD_W = 132

    private data class SamplePokemon(val species: String, val hp: Float, val level: Int = 50,
                                     val fainted: Boolean = false, val burned: Boolean = false,
                                     val gender: Gender? = null)

    fun render(context: DrawContext, page: String, width: Int, height: Int) {
        val korean = MinecraftClient.getInstance().options.language == "ko_kr"
        context.fill(0, 0, width, height, 0x4906101E)
        if (page == "draft-switch" || page == "draft-target" || page == "draft-target-triple")
            BattleModalVignette.draw(context, width, height, 1f)
        drawCaption(context, page, width, korean)
        if (page == "draft-hud-split") {
            BattleHudSplitDraft.render(context, width, korean)
            return
        }
        if (page == "draft-hud-edge") {
            BattleHudEdgeDraft.render(context, width, korean)
            return
        }
        val count = when (page) {
            "draft-hud-double", "draft-target" -> 2
            "draft-hud-triple", "draft-target-triple" -> 3
            else -> 1
        }
        if (count > 1) {
            val targetPage = page == "draft-target" || page == "draft-target-triple"
            if (targetPage) {
                BattleHudEdgeDraft.render(context, width, korean, count)
            } else {
                val allies = listOf(SamplePokemon("pikachu", .72f, gender = Gender.MALE),
                    SamplePokemon("bulbasaur", .91f, gender = Gender.FEMALE), SamplePokemon("eevee", .48f))
                val opponents = listOf(SamplePokemon("charizard", .36f, gender = Gender.FEMALE),
                    SamplePokemon("venusaur", .54f, burned = true), SamplePokemon("blastoise", .67f))
                repeat(count) { index ->
                    drawHud(context, 10, 22 + index * 33, allies[index], true, korean, compact = true)
                    drawHud(context, width - COMPACT_CARD_W - 10, 22 + index * 33,
                        opponents[index], false, korean, compact = true)
                }
            }
            if (targetPage)
                drawTarget(context, width, height, count)
            return
        }
        drawHud(context, 10, 28, SamplePokemon("pikachu", .72f, gender = Gender.MALE), true, korean)
        drawHud(context, width - CARD_W - 10, 28, SamplePokemon("charizard", .36f, burned = true, gender = Gender.FEMALE), false, korean)
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
        val cardWidth = if (compact) COMPACT_CARD_W else CARD_W
        val height = if (compact) 30 else CARD_H
        val directionalCuts = if (self) BattleCornerCuts(topLeft = 4, bottomRight = 15)
            else BattleCornerCuts(topRight = 4, bottomLeft = 15)
        BattleSurfaceRenderer.draw(context, x, y, cardWidth, height,
            BattleUiTheme.panel.copy(top = 0xE5284054.toInt(), bottom = 0xE70C192B.toInt(),
                borderWidth = 0, cornerCuts = directionalCuts))
        context.fill(if (self) x else x + cardWidth - 3, y + 5,
            if (self) x + 3 else x + cardWidth, y + height - 6, accent)
        val portraitSize = if (compact) 21 else 28
        val portraitX = if (self) x + 6 else x + cardWidth - portraitSize - 6
        val portraitY = y + if (compact) 3 else 5
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 2, portraitY + 2, portraitSize - 4, null,
            Identifier.of("cobblemon", sample.species), emptySet(),
            UUID.nameUUIDFromBytes(sample.species.toByteArray()), sample.fainted, null, self, { it }, 1f)

        val contentX = if (self) x + portraitSize + 10 else x + 8
        val contentRight = if (self) x + cardWidth - 8 else portraitX - 5
        val name = Text.translatable("cobblemon.species.${sample.species}.name").string
        val font = MinecraftClient.getInstance().textRenderer
        if (compact) {
            BattleGenderText.draw(context, name, sample.gender, contentX, y + 3,
                contentRight - contentX - 26, 1f)
            rightText(context, "${sample.level}", contentRight, y + 3, BattleUiTheme.MUTED)
            val fullBarRight = minOf(contentX + 48, contentRight - if (self) 43 else 31)
            val barRight = contentX + BattleHealthBarLayout.shortWidth(fullBarRight - contentX)
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
        val statusLabel = if (sample.burned) (if (korean) "화상" else "BRN") else null
        if (sample.burned) {
            val badgeX = contentRight - font.getWidth(statusLabel!!) - 4
            context.fill(badgeX, y + 32, contentRight, y + 41,
                BattleStatusPalette.background(Statuses.BURN.showdownName))
            text(context, statusLabel, badgeX + 2, y + 32, 0xFF182337.toInt())
        }
        BattleGenderText.draw(context, name, sample.gender, contentX, y + 6,
            contentRight - contentX - 30, 1f)
        rightText(context, "${sample.level}", contentRight, y + 6, BattleUiTheme.MUTED)
        val barY = y + 20
        val barRight = contentX + BattleHealthBarLayout.shortWidth(contentRight - contentX)
        context.fill(contentX, barY, barRight, barY + 6, BattleUiTheme.TRACK)
        context.fill(contentX + 1, barY + 1,
            contentX + 1 + ((barRight - contentX - 2) * sample.hp).toInt(), barY + 5, hpColor(sample.hp))
        rightText(context, if (self) "${(sample.hp * 120).toInt()}/120" else "${(sample.hp * 100).toInt()}%",
            if (statusLabel == null) contentRight else contentRight - font.getWidth(statusLabel) - 8,
            y + 32, BattleUiTheme.TEXT)
    }

    private fun drawCommands(context: DrawContext, width: Int, height: Int, rail: Boolean) {
        val labels = listOf("fight", "switch", "capture", "run")
        val x = width - 90
        val top = height - 126
        labels.forEachIndexed { index, key ->
            val y = top + index * 30
            val style = when {
                index == 0 && rail -> BattleUiTheme.primary.copy(cut = 6, corners = 0b1001)
                index == 0 -> BattleUiTheme.primary.copy(cut = 6, corners = 0b1001)
                index == 3 -> BattleUiTheme.danger.copy(backgroundOpacity = 1f,
                    cut = 5, corners = 0b0101)
                index == 2 -> BattleUiTheme.capture.copy(backgroundOpacity = 1f,
                    cut = 4, corners = 0b1010)
                else -> BattleUiTheme.secondary.copy(backgroundOpacity = 1f,
                    cut = if (rail) 4 else 0)
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
            SamplePokemon("pikachu", .72f, gender = Gender.MALE), SamplePokemon("bulbasaur", .91f, gender = Gender.FEMALE),
            SamplePokemon("eevee", .48f, gender = Gender.FEMALE), SamplePokemon("squirtle", .67f),
            SamplePokemon("charmander", .21f, burned = true), SamplePokemon("jigglypuff", 0f, fainted = true)
        )
        val cards = samples.mapIndexed { index, sample ->
            val heldItemId = when (sample.species) {
                "pikachu" -> "light_ball"
                "bulbasaur" -> "miracle_seed"
                "eevee" -> "leftovers"
                else -> null
            }
            val heldItem = heldItemId?.let {
                ItemStack(Registries.ITEM.get(Identifier.of("cobblemon", it)))
            } ?: ItemStack.EMPTY
            BattlePartyCard(
                Identifier.of("cobblemon", sample.species), emptySet(),
                UUID.nameUUIDFromBytes(sample.species.toByteArray()),
                Text.translatable("cobblemon.species.${sample.species}.name"), sample.level,
                sample.hp, sample.fainted, index == 0,
                if (sample.burned) Statuses.BURN else null,
                if (sample.burned) Text.translatable("cobblemon_battle_ui.switch.status.brn") else null,
                BattlePartyDetails(listOf("quickattack" to "25/30", "bite" to "25/25",
                    "babydolleyes" to "30/30", "swift" to "20/20").map { (move, pp) ->
                    BattlePartyMove(Moves.getByName(move)?.displayName?.string ?: move, pp,
                        Moves.getByName(move)?.elementalType)
                }, Text.translatable("cobblemon.ability.runaway").string,
                    if (heldItem.isEmpty) Text.translatable("cobblemon_battle_ui.switch.none").string
                    else heldItem.name.string,
                    PokemonSpecies.getByIdentifier(Identifier.of("cobblemon", sample.species))?.standardForm?.let {
                        listOfNotNull(it.primaryType, it.secondaryType)
                    } ?: emptyList()), sample.gender, heldItem
            )
        }
        val opponents = listOf("charizard", "venusaur", "blastoise").map { species ->
            BattleOpponentCard(Identifier.of("cobblemon", species), emptySet(),
                UUID.nameUUIDFromBytes("opponent-$species".toByteArray()), false, null)
        }
        BattleSwitchRenderer.draw(context, width, height, cards, focused = 2, opponents = opponents)
    }

    /** Functionless target-page proposal, deliberately separate from Cobblemon's input path. */
    private fun drawTarget(context: DrawContext, width: Int, height: Int, slots: Int) {
        BattleTargetRenderer.drawChrome(context, width, height, slots)
        val opponents = if (slots == 2) listOf("squirtle", "charizard")
            else listOf("squirtle", "charizard", "meowth")
        val allies = if (slots == 2) listOf("pikachu", "bulbasaur")
            else listOf("pikachu", "bulbasaur", "eevee")
        allies.forEachIndexed { column, species ->
            val card = previewTargetCard(species, ally = true, selectable = column != 0, focused = false)
            val slot = BattleScreenGeometry.targetTile(width, height, slots, 0, column)
            val rect = BattleScreenGeometry.targetCard(slot)
            BattleTargetRenderer.drawCard(context, rect, card)
        }
        opponents.forEachIndexed { column, species ->
            val card = previewTargetCard(species, ally = false, selectable = true, focused = column == 1)
            val slot = BattleScreenGeometry.targetTile(width, height, slots, 1, column)
            val rect = BattleScreenGeometry.targetCard(slot)
            BattleTargetRenderer.drawCard(context, rect, card)
        }
    }

    private fun previewTargetCard(species: String, ally: Boolean, selectable: Boolean,
                                  focused: Boolean) = BattleTargetCard(
        Identifier.of("cobblemon", species), emptySet(), UUID.nameUUIDFromBytes(species.toByteArray()),
        Text.translatable("cobblemon.species.$species.name"), ally, selectable, false, focused)

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
