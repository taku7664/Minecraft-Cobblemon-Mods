package jbro.cobblemon.npc.client

import com.mojang.blaze3d.vertex.PoseStack
import jbro.cobblemon.npc.content.NpcEntity
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation

/** Draws an NPC as a player in its skin, on the slim or wide model the skin asks for. */
class NpcEntityRenderer(context: EntityRendererProvider.Context) :
    LivingEntityRenderer<NpcEntity, PlayerModel<NpcEntity>>(context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val wide = model
    private val slim = PlayerModel<NpcEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    override fun render(entity: NpcEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        model = if (NpcSkins.skin(entity.skinName).model() == PlayerSkin.Model.SLIM) slim else wide
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }

    override fun getTextureLocation(entity: NpcEntity): ResourceLocation = NpcSkins.skin(entity.skinName).texture()

    // A player's own scale, so an NPC stands as tall as the players around it.
    override fun scale(entity: NpcEntity, pose: PoseStack, partialTick: Float) = pose.scale(0.9375f, 0.9375f, 0.9375f)
}
