package jbro.cobblemon.npc.client

import com.mojang.blaze3d.vertex.PoseStack
import jbro.cobblemon.npc.content.NpcEntity
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation

/**
 * Draws an NPC as a player in its skin, on the slim or wide model the skin asks for, or as the Pokemon its skin
 * names, on Cobblemon's model at a wild one's size.
 */
class NpcEntityRenderer(context: EntityRendererProvider.Context) :
    LivingEntityRenderer<NpcEntity, EntityModel<NpcEntity>>(context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val wide = model
    private val slim = PlayerModel<NpcEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)
    private val pokemon = NpcPokemonModel()

    override fun render(entity: NpcEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val ref = NpcSkinRef.parse(entity.skinName)
        if (ref is NpcSkinRef.Pokemon && pokemon.prepare(entity, ref, buffers)) {
            model = pokemon
            // The shadow follows the hitbox, which is already the Pokemon's size.
            shadowRadius = minOf(entity.bbWidth, 4f) / 1.5f
            try {
                super.render(entity, yaw, partialTick, pose, buffers, light)
            } finally {
                pokemon.finish()
            }
            return
        }
        model = if (NpcSkins.skin(entity.skinName).model() == PlayerSkin.Model.SLIM) slim else wide
        shadowRadius = 0.5f
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }

    override fun getTextureLocation(entity: NpcEntity): ResourceLocation =
        if (model === pokemon) pokemon.texture ?: NpcSkins.skin(entity.skinName).texture()
        else NpcSkins.skin(entity.skinName).texture()

    // A player's own scale, so an NPC stands as tall as the players around it; a Pokemon at its form's base scale.
    override fun scale(entity: NpcEntity, pose: PoseStack, partialTick: Float) {
        val scale = if (model === pokemon) pokemon.scale else 0.9375f
        pose.scale(scale, scale, scale)
    }
}
