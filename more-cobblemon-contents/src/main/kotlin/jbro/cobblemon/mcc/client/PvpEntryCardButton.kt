package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionPartySlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** A selectable entry card for one of the viewer's own Pokemon during PvP entry selection. */
internal class PvpEntryCardButton(
    bounds: TowerPlayRect,
    private val content: TowerPartyCardContentLayout,
    private val pokemon: PvpSelectionPartySlot,
    private val selectionPosition: Int?,
    private val speciesName: Component,
    private val portraits: MccPokemonPortraitRenderer,
    private val press: () -> Unit,
) : AbstractButton(
    bounds.left,
    bounds.top,
    bounds.width,
    bounds.height,
    narrationMessage(selectionPosition, speciesName, pokemon.battleLevel),
) {
    override fun onPress() = press()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MccGuiSurface.drawButton(
            graphics,
            TowerPlayRect(x, y, width, height),
            active,
            isHoveredOrFocused,
            selectionPosition != null,
            MccGuiPalette.ACCENT_PRIMARY,
        )
        graphics.fill(
            content.portrait.left,
            content.portrait.top,
            content.portrait.right,
            content.portrait.bottom,
            MccGuiPalette.PANEL_ALT,
        )
        portraits.render(
            graphics,
            pokemon.pokemonId,
            pokemon.speciesId,
            pokemon.formId,
            content.portrait,
            partialTick,
            isHoveredOrFocused,
        )
        val font = Minecraft.getInstance().font
        val name = if (selectionPosition == null) {
            speciesName
        } else {
            Component.translatable(key("party_entry.order_name"), selectionPosition, speciesName)
        }
        val details = Component.translatable(
            key("party_entry.details"),
            pokemon.originalLevel,
            pokemon.battleLevel,
        )
        drawClipped(
            graphics,
            font,
            name,
            content.nameTop,
            if (selectionPosition == null) MccGuiPalette.TEXT_PRIMARY else MccGuiPalette.ACCENT_PRIMARY,
        )
        drawClipped(graphics, font, details, content.detailsTop, MccGuiPalette.TEXT_SECONDARY)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private fun drawClipped(graphics: GuiGraphics, font: Font, text: Component, top: Int, color: Int) {
        val width = (content.textRight - content.textLeft).coerceAtLeast(1)
        val line = font.split(text, width).firstOrNull() ?: return
        graphics.drawString(font, line, content.textLeft, top, color, false)
    }

    private companion object {
        fun key(path: String) = "screen.more_cobblemon_contents.pvp.$path"

        fun narrationMessage(
            selectionPosition: Int?,
            speciesName: Component,
            battleLevel: Int,
        ): Component = if (selectionPosition == null) {
            Component.translatable(key("party_entry.narration.available"), speciesName, battleLevel)
        } else {
            Component.translatable(
                key("party_entry.narration.selected"),
                selectionPosition,
                speciesName,
                battleLevel,
            )
        }
    }
}
