package jbro.cobblemon.mcc.pwt.client

import jbro.cobblemon.mcc.pwt.network.PwtEntrantView
import jbro.cobblemon.mcc.pwt.network.PwtRunView
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.UiPixelBox
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import jbro.cobblemon.uikit.CobblemonUiThemes

/**
 * The tournament bracket: faces only, the challenger ringed, losers dimmed, the winners' paths lit and the match
 * about to be played blinking. Hovering a face names the trainer, their generation and their ace.
 */
internal class PwtBracketWidget(r: UiRect, private val run: PwtRunView) :
    AbstractWidget(r.x, r.y, r.width, r.height, Component.empty()) {
    private val layout = PwtBracketLayout.calculate(r, run.winners)

    init { active = false }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val layout = layout ?: return
        val theme = CobblemonUiThemes.registry.snapshot()
        layout.lines.forEach { line ->
            val color = if (line.lit) theme.colors.accentGood else theme.colors.shell
            line.segments.forEach { g.fill(it.x, it.y, it.right, it.bottom, color) }
        }
        scaledItem(g, ItemStack(Items.NETHER_STAR), layout.trophy)
        // The match the challenger plays next blinks: their box and the opponent's.
        val playerSlot = run.entrants.indexOfFirst { it.player }
        val current = if (run.finished || run.battling) emptySet() else currentMatch(playerSlot)
        val blinkOn = PwtClientState.ticks / 10 % 2 == 0L
        var hovered: Pair<PwtEntrantView, Int>? = null
        layout.boxes.forEach { box ->
            val rect = box.rect
            val slot = box.slot
            val entrant = slot?.let(run.entrants::get)
            val edge = when {
                box.round == 3 && slot != null -> theme.colors.accentCaution
                slot != null && eliminated(slot, box.round) -> theme.colors.shell
                else -> theme.colors.borderBright
            }
            UiPixelBox.draw(g, theme, rect.x, rect.y, rect.width, rect.height, edge, theme.pixelDecorations?.titleBarShade ?: theme.colors.border)
            if (entrant == null) {
                val font = Minecraft.getInstance().font
                g.drawString(font, "?", rect.x + (rect.width - font.width("?")) / 2, rect.y + (rect.height - 8) / 2, theme.colors.textDim, false)
                return@forEach
            }
            face(entrant)?.let { PlayerFaceRenderer.draw(g, it, rect.x + 1, rect.y + 1, rect.width - 2) }
            if (eliminated(slot, box.round)) g.fill(rect.x + 1, rect.y + 1, rect.right - 1, rect.bottom - 1, 0x99000000.toInt())
            if (entrant.player) UiPixelBox.ring(g, theme, rect.x - 2, rect.y - 2, rect.width + 4, rect.height + 4, theme.colors.accentCaution, 2)
            if (slot in current && box.round == run.round && blinkOn)
                UiPixelBox.ring(g, theme, rect.x - 1, rect.y - 1, rect.width + 2, rect.height + 2, theme.colors.textPrimary, 1)
            if (mouseX >= rect.x && mouseX < rect.right && mouseY >= rect.y && mouseY < rect.bottom) hovered = entrant to box.round
        }
        hovered?.let { (entrant, _) -> Minecraft.getInstance().screen?.setTooltipForNextRenderPass(tooltip(entrant)) }
    }

    /** The two slots of the challenger's match this round. */
    private fun currentMatch(playerSlot: Int): Set<Int> {
        val alive = if (run.round == 0) (0 until 8).toList() else run.winners[run.round - 1]
        val index = alive.indexOf(playerSlot)
        if (index < 0) return emptySet()
        return setOf(alive[index], alive[index xor 1])
    }

    /** Shown dimmed in [round]'s box: the slot lost that round's match. */
    private fun eliminated(slot: Int, round: Int): Boolean = run.winners.getOrNull(round)?.let { slot !in it } ?: false

    private fun tooltip(entrant: PwtEntrantView): Component {
        if (entrant.player) return Component.literal(entrant.nameKey)
        val text = Component.translatable(entrant.nameKey).copy()
        if (entrant.generation > 0) text.append("\n").append(pwt("generation", entrant.generation))
        entrant.aceSpecies?.let { text.append("\n").append(pwt("ace", Component.translatable("cobblemon.species.${it.substringAfter(':')}.name"))) }
        return text
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}

private val faces = HashMap<String, ResourceLocation?>()

/** The trainer's skin, or the challenger's own; null when the skin's pack is missing. */
private fun face(entrant: PwtEntrantView): ResourceLocation? {
    if (entrant.player) return Minecraft.getInstance().player?.skin?.texture()
    val skin = entrant.skin ?: return null
    return faces.getOrPut(skin) {
        ResourceLocation.tryParse(skin)?.takeIf { Minecraft.getInstance().resourceManager.getResource(it).isPresent }
    }
}

private fun scaledItem(g: GuiGraphics, item: ItemStack, rect: UiRect) {
    g.pose().pushPose(); g.pose().translate(rect.x.toFloat(), rect.y.toFloat(), 0f); g.pose().scale(rect.width / 16f, rect.height / 16f, 1f)
    g.renderItem(item, 0, 0); g.pose().popPose()
}

internal fun pwt(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents_pwt.$key", *args)

