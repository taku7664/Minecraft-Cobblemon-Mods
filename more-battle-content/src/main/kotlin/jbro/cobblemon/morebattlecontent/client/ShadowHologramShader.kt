package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.client.render.RenderTypeTextureBridge
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation

internal object ShadowHologramShader {
    private val shaderId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "shadow_hologram")
    private val renderTypes = ConcurrentHashMap<ResourceLocation, RenderType>()

    @Volatile
    private var shader: ShaderInstance? = null

    fun register() {
        CoreShaderRegistrationCallback.EVENT.register { context ->
            try {
                context.register(shaderId, DefaultVertexFormat.NEW_ENTITY) { loaded ->
                    shader = loaded
                    renderTypes.clear()
                    MoreBattleContent.LOGGER.info("Loaded MBC Shadow hologram shader")
                }
            } catch (exception: IOException) {
                shader = null
                renderTypes.clear()
                MoreBattleContent.LOGGER.error("Failed to load MBC Shadow hologram shader; using translucent fallback", exception)
            }
        }
    }

    fun buffer(
        delegate: MultiBufferSource,
        requestedType: RenderType,
        fallback: () -> VertexConsumer,
    ): VertexConsumer {
        if (shader == null) return fallback()
        if (!supports(requestedType.format())) return fallback()
        val texture = RenderTypeTextureBridge.textureOf(requestedType) ?: return fallback()
        return delegate.getBuffer(renderTypes.computeIfAbsent(texture, ::createRenderType))
    }

    internal fun supports(format: VertexFormat): Boolean = format == DefaultVertexFormat.NEW_ENTITY

    fun setCameraWorldPosition(x: Double, y: Double, z: Double) {
        shader?.getUniform("CameraWorldPosition")?.set(x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun createRenderType(texture: ResourceLocation): RenderType = RenderType.create(
        "mbc_shadow_hologram",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        1536,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(::activeShader))
            .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
            .setOverlayState(RenderStateShard.NO_OVERLAY)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .createCompositeState(false),
    )

    private fun activeShader(): ShaderInstance = shader
        ?: requireNotNull(GameRenderer.getRendertypeEntityTranslucentShader()) {
            "Neither the MBC Shadow shader nor the vanilla translucent entity shader is loaded"
        }

}
