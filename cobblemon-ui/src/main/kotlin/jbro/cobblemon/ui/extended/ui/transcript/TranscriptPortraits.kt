package jbro.cobblemon.ui.extended.ui.transcript

import com.cobblemon.mod.common.api.gui.drawPosablePortrait
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay
import jbro.cobblemon.ui.extended.CobblemonUi
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.math.MatrixStack

/** Uses Cobblemon's PORTRAIT pose, not a full-body sprite or an external portrait pack. */
internal object TranscriptPortraits {
    private val faces = mutableMapOf<TranscriptSpeaker, FloatingState>()
    private val failed = mutableSetOf<TranscriptSpeaker>()

    fun clear() { faces.clear(); failed.clear() }

    fun draw(context: DrawContext, speaker: TranscriptSpeaker, x: Int, y: Int, size: Int): Boolean {
        if (speaker in failed) return false
        context.draw()
        context.enableScissor(x, y, x + size, y + size)
        // Isolate third-party model matrix pushes: an exception inside a custom poser
        // must not leave the caller's entire GUI matrix stack unbalanced.
        val matrices = MatrixStack()
        matrices.peek().positionMatrix.set(context.matrices.peek().positionMatrix)
        matrices.peek().normalMatrix.set(context.matrices.peek().normalMatrix)
        try {
            val scale = size / BattleOverlay.PORTRAIT_DIAMETER.toFloat()
            matrices.translate(x + size / 2.0, y - 5.0 * scale, 0.0)
            matrices.scale(scale, scale, scale)
            val state = faces.getOrPut(speaker) { FloatingState().also { it.currentAspects = speaker.aspects } }
            val species = requireNotNull(PokemonSpecies.getByIdentifier(speaker.species))
            // Same portrait framing as Cobblemon's 28px battle HUD heads.
            drawPosablePortrait(identifier = speaker.species, matrixStack = matrices,
                scale = 18f, contextScale = species.getForm(speaker.aspects).baseScale,
                reversed = !speaker.left, state = state, partialTicks = 0f, doQuirks = false)
            return true
        } catch (error: Exception) {
            // A broken species/resource-pack model must not crash the battle screen.
            failed.add(speaker)
            CobblemonUi.LOGGER.warn("Battle log portrait unavailable: {}", speaker.species, error)
            return false
        } finally {
            context.draw()
            context.disableScissor()
        }
    }
}
