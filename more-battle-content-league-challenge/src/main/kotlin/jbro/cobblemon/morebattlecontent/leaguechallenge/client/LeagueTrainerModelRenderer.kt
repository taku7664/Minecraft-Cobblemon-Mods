package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import kotlin.math.min

internal data class LeagueTrainerModelPlacement(
    val centerX: Int,
    val centerY: Int,
    val scale: Int
) {
    companion object {
        fun calculate(viewport: LeagueUiRect, entityHeight: Float): LeagueTrainerModelPlacement {
            require(entityHeight > 0f) { "Trainer model height must be positive" }
            val heightScale = (viewport.height * HEIGHT_USAGE / entityHeight).toInt()
            val widthScale = (viewport.width * WIDTH_USAGE).toInt()
            return LeagueTrainerModelPlacement(
                centerX = viewport.left + viewport.width / 2,
                centerY = viewport.top + viewport.height / 2,
                scale = min(heightScale, widthScale).coerceIn(1, MAX_SCALE)
            )
        }
    }
}

internal class LeagueTrainerModelRenderer {
    private var model: PlayerModel<LivingEntity>? = null

    fun render(graphics: GuiGraphics, viewport: LeagueUiRect): Boolean {
        val playerModel = model ?: PlayerModel<LivingEntity>(
            Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.PLAYER),
            false
        ).also { baked ->
            baked.setAllVisible(true)
            baked.leftArm.zRot = -0.08f
            baked.rightArm.zRot = 0.08f
            model = baked
        }
        val placement = LeagueTrainerModelPlacement.calculate(viewport, PLAYER_HEIGHT)
        val pose = graphics.pose()
        graphics.flush()
        graphics.enableScissor(viewport.left + 1, viewport.top + 1, viewport.right - 1, viewport.bottom - 1)
        try {
            pose.pushPose()
            try {
                pose.translate(
                    placement.centerX.toDouble(),
                    (viewport.top + placement.scale / 2f).toDouble(),
                    MODEL_DEPTH
                )
                pose.scale(placement.scale.toFloat(), placement.scale.toFloat(), -placement.scale.toFloat())
                val buffer = graphics.bufferSource().getBuffer(playerModel.renderType(PLACEHOLDER_TEXTURE))
                playerModel.renderToBuffer(pose, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
                graphics.flush()
            } finally {
                pose.popPose()
            }
        } finally {
            graphics.disableScissor()
        }
        return true
    }

    companion object {
        private const val PLAYER_HEIGHT = 1.5f
        private const val MODEL_DEPTH = 80.0
        private val PLACEHOLDER_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png")
    }
}

private const val HEIGHT_USAGE = 0.90f
private const val WIDTH_USAGE = 0.88f
private const val MAX_SCALE = 120
