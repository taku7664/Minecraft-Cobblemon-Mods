package jbro.cobblemon.ui.extended.ui.transcript

import com.cobblemon.mod.common.api.gui.drawPosablePortrait
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay
import jbro.cobblemon.ui.extended.CobblemonUi
import net.minecraft.client.gui.GuiGraphics
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.joml.Vector3f

/** Uses Cobblemon's PORTRAIT pose, not a full-body sprite or an external portrait pack. */
internal object TranscriptPortraits {
    private val faces = mutableMapOf<TranscriptSpeaker, FloatingState>()
    private val failed = mutableSetOf<TranscriptSpeaker>()

    private val failedEntities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LivingEntity, Boolean>())

    fun clear() { faces.clear(); failed.clear(); failedEntities.clear() }

    /**
     * A speaker's head, from its own model as the world renders it (a trainer's skin and pose), framed like the
     * Pokemon portraits and turned toward the middle of the history. False when it cannot be drawn.
     */
    fun draw(context: GuiGraphics, entity: LivingEntity, left: Boolean, x: Int, y: Int, size: Int): Boolean {
        if (entity.isRemoved || entity in failedEntities) return false
        context.flush()
        context.enableScissor(x, y, x + size, y + size)
        val body = entity.yBodyRot
        val yaw = entity.yRot
        val pitch = entity.xRot
        val headOld = entity.yHeadRotO
        val head = entity.yHeadRot
        try {
            // A three-quarter turn toward the other side, as the inventory turns the player toward the mouse.
            val turn = if (left) -25f else 25f
            entity.yBodyRot = 180f + turn
            entity.yRot = 180f + turn
            entity.xRot = 0f
            entity.yHeadRot = entity.yRot
            entity.yHeadRotO = entity.yRot
            // The head filling most of the frame. Placing the eye-height point a quarter down the frame puts the
            // drawn eyes near the middle: on a captured NPC trainer the model's eyes sat lower than its eye height.
            val size3 = entity.scale.coerceAtLeast(0.1f)
            val scale = size * 1.45f / size3
            val lift = entity.eyeHeight / size3 + 0.05f
            InventoryScreen.renderEntityInInventory(context, x + size / 2f, y + size * 0.27f, scale,
                Vector3f(0f, lift, 0f), Quaternionf().rotateZ(Math.PI.toFloat()), null, entity)
            return true
        } catch (error: Exception) {
            failedEntities.add(entity)
            CobblemonUi.LOGGER.warn("Battle log speaker portrait unavailable: {}", entity.type, error)
            return false
        } finally {
            entity.yBodyRot = body
            entity.yRot = yaw
            entity.xRot = pitch
            entity.yHeadRotO = headOld
            entity.yHeadRot = head
            context.flush()
            context.disableScissor()
        }
    }

    fun draw(context: GuiGraphics, speaker: TranscriptSpeaker, x: Int, y: Int, size: Int): Boolean {
        if (speaker in failed) return false
        context.flush()
        context.enableScissor(x, y, x + size, y + size)
        // Isolate third-party model matrix pushes: an exception inside a custom poser
        // must not leave the caller's entire GUI matrix stack unbalanced.
        val matrices = PoseStack()
        matrices.last().pose().set(context.pose().last().pose())
        matrices.last().normal().set(context.pose().last().normal())
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
            context.flush()
            context.disableScissor()
        }
    }
}
