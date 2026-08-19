package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPartySlot
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

internal class TowerPartyCardButton(
    bounds: TowerPlayRect,
    private val content: TowerPartyCardContentLayout,
    private val pokemon: TowerPlayPartySlot,
    private val selectionPosition: Int?,
    private val speciesName: Component,
    private val heldItemName: Component,
    private val portraits: MbcPokemonPortraitRenderer,
    private val press: () -> Unit,
) : AbstractButton(
    bounds.left,
    bounds.top,
    bounds.width,
    bounds.height,
    narrationMessage(selectionPosition, speciesName, pokemon.battleLevel, heldItemName),
) {
    override fun onPress() = press()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MbcGuiSurface.drawButton(
            graphics,
            TowerPlayRect(x, y, width, height),
            active,
            isHoveredOrFocused,
            selectionPosition != null,
            MbcGuiPalette.ACCENT_PRIMARY,
        )
        graphics.fill(
            content.portrait.left,
            content.portrait.top,
            content.portrait.right,
            content.portrait.bottom,
            MbcGuiPalette.PANEL_ALT,
        )
        portraits.render(graphics, pokemon, content.portrait, partialTick, isHoveredOrFocused)
        val font = net.minecraft.client.Minecraft.getInstance().font
        val name = if (selectionPosition == null) {
            speciesName
        } else {
            Component.translatable(
                "screen.cobblemon_more_battle_content.tower.party_entry.order_name",
                selectionPosition,
                speciesName,
            )
        }
        val details = Component.translatable(
            "screen.cobblemon_more_battle_content.tower.party_entry.details",
            pokemon.battleLevel,
            heldItemName,
        )
        drawClipped(
            graphics,
            font,
            name,
            content.nameTop,
            if (selectionPosition == null) MbcGuiPalette.TEXT_PRIMARY else MbcGuiPalette.ACCENT_PRIMARY,
        )
        drawClipped(graphics, font, details, content.detailsTop, MbcGuiPalette.TEXT_SECONDARY)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private fun drawClipped(graphics: GuiGraphics, font: Font, text: Component, top: Int, color: Int) {
        val width = (content.textRight - content.textLeft).coerceAtLeast(1)
        val line = font.split(text, width).firstOrNull() ?: return
        graphics.drawString(font, line, content.textLeft, top, color, false)
    }

    private companion object {
        fun narrationMessage(
            selectionPosition: Int?,
            speciesName: Component,
            battleLevel: Int,
            heldItemName: Component,
        ): Component = if (selectionPosition == null) {
            Component.translatable(
                "screen.cobblemon_more_battle_content.tower.party_entry.narration.available",
                speciesName,
                battleLevel,
                heldItemName,
            )
        } else {
            Component.translatable(
                "screen.cobblemon_more_battle_content.tower.party_entry.narration.selected",
                selectionPosition,
                speciesName,
                battleLevel,
                heldItemName,
            )
        }
    }
}
