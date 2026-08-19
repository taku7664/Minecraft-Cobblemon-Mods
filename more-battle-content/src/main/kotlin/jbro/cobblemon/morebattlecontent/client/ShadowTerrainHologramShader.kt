package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import java.io.IOException
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation

internal object ShadowTerrainHologramShader {
    private val shaderId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "shadow_terrain_hologram")

    @Volatile
    private var shader: ShaderInstance? = null

    fun register() {
        CoreShaderRegistrationCallback.EVENT.register { context ->
            try {
                context.register(shaderId, DefaultVertexFormat.POSITION_TEX) { loaded ->
                    shader = loaded
                    MoreBattleContent.LOGGER.info("Loaded MBC Shadow terrain hologram compositor")
                }
            } catch (exception: IOException) {
                shader = null
                MoreBattleContent.LOGGER.error(
                    "Failed to load MBC Shadow terrain hologram compositor; terrain effect is disabled",
                    exception,
                )
            }
        }
    }

    fun activeShader(): ShaderInstance? = shader
}
