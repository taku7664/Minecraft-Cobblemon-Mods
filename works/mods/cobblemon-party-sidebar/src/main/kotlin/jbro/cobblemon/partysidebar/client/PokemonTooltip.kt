package jbro.cobblemon.partysidebar.client

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import org.joml.Matrix4f

/**
 * The summary shown over a party Pokemon:
 * level, name and gender; HP; nature and ability; the moves; then IVs and EVs per stat in aligned columns.
 */
internal object PokemonTooltip {
    private const val MALE_COLOR = 0x5DA9FF
    private const val FEMALE_COLOR = 0xFF7DAE
    private const val LABEL_COLOR = 0xAAAAAA
    private const val RAISED_COLOR = 0xFF8A80
    private const val LOWERED_COLOR = 0x80B0FF

    fun render(graphics: GuiGraphics, pokemon: Pokemon, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val lines = textLines(pokemon).map { ClientTooltipComponent.create(it.visualOrderText) } + StatTable(pokemon)
        graphics.renderTooltipInternal(font, lines, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE)
    }

    private fun textLines(pokemon: Pokemon): List<Component> = buildList {
        val title = key("level", pokemon.level).copy().append(" ").append(pokemon.getDisplayName())
        genderSymbol(pokemon.gender)?.let { title.append(" ").append(it) }
        add(title)
        add(key("hp", pokemon.currentHealth, pokemon.maxHealth).copy().withStyle { it.withColor(hpColor(pokemon)) })
        add(labelled("nature", Component.translatable(pokemon.effectiveNature.displayName)))
        add(labelled("ability", Component.translatable(pokemon.ability.displayName)))
        pokemon.moveSet.getMoves().forEach { add(it.displayName) }
    }

    private fun genderSymbol(gender: Gender): Component? = when (gender) {
        Gender.MALE -> Component.literal("♂").withStyle { it.withColor(MALE_COLOR) }
        Gender.FEMALE -> Component.literal("♀").withStyle { it.withColor(FEMALE_COLOR) }
        Gender.GENDERLESS -> null
    }

    private fun hpColor(pokemon: Pokemon): Int {
        val ratio = if (pokemon.maxHealth > 0) pokemon.currentHealth.toFloat() / pokemon.maxHealth else 0f
        return when {
            ratio > 0.5f -> 0x55FF55
            ratio > 0.2f -> 0xFFFF55
            else -> 0xFF5555
        }
    }

    private fun labelled(name: String, value: Component): Component =
        key(name).copy().withStyle(ChatFormatting.GRAY).append(" ").append(value.copy().withStyle(ChatFormatting.WHITE))

    private fun key(name: String, vararg args: Any): MutableComponent =
        Component.translatable("cobblemon_party_sidebar.tooltip.$name", *args)

    /** Stat names on the left, IVs and EVs right-aligned under their headings; nature-raised and lowered stats tinted. */
    private class StatTable(pokemon: Pokemon) : ClientTooltipComponent {
        private data class Row(val label: Component, val iv: String, val ev: String, val color: Int)

        private val ivHeading = key("iv")
        private val evHeading = key("ev")
        private val rows = STATS.map { stat ->
            val nature = pokemon.effectiveNature
            val color = when (stat) {
                nature.increasedStat -> RAISED_COLOR
                nature.decreasedStat -> LOWERED_COLOR
                else -> 0xFFFFFF
            }
            Row(stat.displayName, (pokemon.ivs[stat] ?: 0).toString(), pokemon.evs.getOrDefault(stat).toString(), color)
        }

        private fun labelWidth(font: Font) = rows.maxOf { font.width(it.label) }

        private fun ivWidth(font: Font) = maxOf(font.width(ivHeading), rows.maxOf { font.width(it.iv) })

        private fun evWidth(font: Font) = maxOf(font.width(evHeading), rows.maxOf { font.width(it.ev) })

        override fun getHeight() = TOP_GAP + (rows.size + 1) * LINE

        override fun getWidth(font: Font) = labelWidth(font) + COLUMN_GAP + ivWidth(font) + COLUMN_GAP + evWidth(font)

        override fun renderText(font: Font, x: Int, y: Int, matrix: Matrix4f, buffer: MultiBufferSource.BufferSource) {
            val ivRight = x + labelWidth(font) + COLUMN_GAP + ivWidth(font)
            val evRight = ivRight + COLUMN_GAP + evWidth(font)
            var lineY = y + TOP_GAP
            draw(font, ivHeading, ivRight - font.width(ivHeading), lineY, LABEL_COLOR, matrix, buffer)
            draw(font, evHeading, evRight - font.width(evHeading), lineY, LABEL_COLOR, matrix, buffer)
            for (row in rows) {
                lineY += LINE
                draw(font, row.label, x, lineY, row.color, matrix, buffer)
                draw(font, Component.literal(row.iv), ivRight - font.width(row.iv), lineY, 0xFFFFFF, matrix, buffer)
                draw(font, Component.literal(row.ev), evRight - font.width(row.ev), lineY, 0xFFFFFF, matrix, buffer)
            }
        }

        private fun draw(font: Font, text: Component, x: Int, y: Int, color: Int, matrix: Matrix4f, buffer: MultiBufferSource.BufferSource) {
            font.drawInBatch(text, x.toFloat(), y.toFloat(), color or OPAQUE, true, matrix, buffer, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT)
        }

        companion object {
            private val STATS: List<Stat> = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
            private const val TOP_GAP = 4
            private const val LINE = 10
            private const val COLUMN_GAP = 8
            private const val OPAQUE = 0xFF000000.toInt()
            private const val FULL_BRIGHT = 0xF000F0
        }
    }
}
