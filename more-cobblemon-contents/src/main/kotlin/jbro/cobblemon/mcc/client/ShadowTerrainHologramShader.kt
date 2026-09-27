package jbro.cobblemon.mcc.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import java.io.IOException
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation

internal object ShadowTerrainHologramShader {
    private val shaderId = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "shadow_terrain_hologram")

    @Volatile
    private var shader: ShaderInstance? = null

    fun register() {
        CoreShaderRegistrationCallback.EVENT.register { context ->
            try {
                context.register(shaderId, DefaultVertexFormat.POSITION_TEX) { loaded ->
                    shader = loaded
                    MoreCobblemonContents.LOGGER.info("Loaded MCC Shadow terrain hologram compositor")
                }
            } catch (exception: IOException) {
                shader = null
                MoreCobblemonContents.LOGGER.error(
                    "Failed to load MCC Shadow terrain hologram compositor; terrain effect is disabled",
                    exception,
                )
            }
        }
    }

    fun activeShader(): ShaderInstance? = shader
}
