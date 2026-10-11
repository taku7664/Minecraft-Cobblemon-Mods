package jbro.cobblemon.npc.client

import com.cobblemon.mod.common.api.scheduling.ClientTaskTracker
import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
import com.cobblemon.mod.common.entity.PoseType
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.content.NpcEntity
import jbro.cobblemon.npc.content.NpcPokemonForms
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import net.minecraft.client.model.EntityModel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import java.util.WeakHashMap

/**
 * Cobblemon's model for an NPC whose skin names a Pokemon, standing in for the player model. It poses and animates
 * the way a wild Pokemon of that form does, without a Pokemon entity behind it: each NPC keeps its own animation
 * state, started over when its skin changes.
 */
class NpcPokemonModel : EntityModel<NpcEntity>(RenderType::entityCutout) {
    private val context = RenderContext().also { it.put(RenderContext.RENDER_STATE, RenderContext.RenderState.WORLD) }
    private val states = WeakHashMap<NpcEntity, Pair<String, NpcPokemonState>>()
    private var poser: PosableModel? = null
    private var state: NpcPokemonState? = null

    /** Cobblemon's texture for the NPC [prepare] last set up. */
    var texture: ResourceLocation? = null
        private set

    /** The form's base scale, the size a wild one is drawn at. */
    var scale = 1f
        private set

    /**
     * Sets up [npc] to be drawn as [ref], or returns false so it is drawn as a player: the species is unknown, its
     * data has not reached this client yet, or Cobblemon cannot build its model.
     */
    fun prepare(npc: NpcEntity, ref: NpcSkinRef.Pokemon, buffers: MultiBufferSource): Boolean {
        poser = null
        val form = NpcPokemonForms.form(ref) ?: return false
        val id = NpcPokemonForms.id(ref)
        return try {
            val key = ref.toString()
            val state = states[npc]?.takeIf { it.first == key }?.second
                ?: NpcPokemonState().also { states[npc] = key to it }
            state.currentAspects = ref.aspects
            val model = VaryingModelRepository.getPoser(id, state)
            model.context = context
            context.put(RenderContext.SPECIES, id)
            context.put(RenderContext.ASPECTS, ref.aspects)
            context.put(RenderContext.SCALE, form.baseScale)
            context.put(RenderContext.POSABLE_STATE, state)
            val texture = VaryingModelRepository.getTexture(id, state)
            this.texture = texture
            context.put(RenderContext.TEXTURE, texture)
            state.currentModel = model
            if (state.currentPose == null) state.setPoseToFirstSuitable(PoseType.STAND)
            model.setLayerContext(buffers, state, VaryingModelRepository.getLayers(id, state))
            scale = form.baseScale
            poser = model
            this.state = state
            true
        } catch (failure: RuntimeException) {
            CobblemonNpc.LOGGER.warn("NPC Pokemon model {} could not be drawn", ref, failure)
            false
        }
    }

    /** Clears what [prepare] handed Cobblemon's model for the frame's glowing layers. */
    fun finish() {
        poser?.resetLayerContext()
        poser = null
    }

    override fun setupAnim(entity: NpcEntity, limbSwing: Float, limbSwingAmount: Float, ageInTicks: Float, headYaw: Float, headPitch: Float) {
        val model = poser ?: return
        val state = state ?: return
        state.updateAge(entity.tickCount)
        state.updatePartialTicks(ageInTicks - entity.tickCount)
        model.applyAnimations(null, state, limbSwing, limbSwingAmount, ageInTicks, headYaw, headPitch)
    }

    override fun renderToBuffer(pose: PoseStack, buffer: VertexConsumer, light: Int, overlay: Int, color: Int) {
        val model = poser ?: return
        // The living-entity renderer lowers models by 24 units for vanilla's; Cobblemon's stand at their origin.
        pose.translate(0.0, 1.5, 0.0)
        model.render(context, pose, buffer, light, overlay, color)
        model.setDefault()
    }

    /** An animation clock driven by the NPC's own age, since there is no Pokemon entity to hold one. */
    private class NpcPokemonState : PosableState() {
        override val schedulingTracker = ClientTaskTracker

        override fun getEntity(): Entity? = null

        override fun updatePartialTicks(partialTicks: Float) {
            currentPartialTicks = partialTicks
        }
    }
}
