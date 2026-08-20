package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.client.render.RenderTypeTextureBridge
import kotlin.math.roundToInt
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation

/**
 * Cyan Pokemon projection: the terrain hologram's look on entity geometry, driven by the model
 * hologram's glitch timeline. Separate from [ShadowHologramShader] because the trainer projection
 * keeps its blackened yellow tint.
 */
internal object ShadowPokemonHologramShader {
    private val shaderId =
        ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "shadow_pokemon_hologram")
    private val renderTypes = ConcurrentHashMap<ResourceLocation, RenderType>()

    @Volatile
    private var shader: ShaderInstance? = null

    fun register() {
        CoreShaderRegistrationCallback.EVENT.register { context ->
            try {
                context.register(shaderId, DefaultVertexFormat.NEW_ENTITY) { loaded ->
                    shader = loaded
                    renderTypes.clear()
                    MoreBattleContent.LOGGER.info("Loaded MBC Pokemon hologram shader")
                }
            } catch (exception: IOException) {
                shader = null
                renderTypes.clear()
                MoreBattleContent.LOGGER.error(
                    "Failed to load MBC Pokemon hologram shader; using translucent fallback",
                    exception,
                )
            }
        }
    }

    /** Wraps [delegate] so every entity render type drawn through it becomes a Pokemon hologram. */
    fun wrap(delegate: MultiBufferSource): MultiBufferSource = PokemonHologramBufferSource(delegate)

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
        "mbc_shadow_pokemon_hologram",
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
            "Neither the MBC Pokemon hologram shader nor the vanilla translucent entity shader is loaded"
        }
}

internal object ShadowPokemonHologramAppearance {
    const val FALLBACK_RED = 0.055F
    const val FALLBACK_GREEN = 0.82F
    const val FALLBACK_BLUE = 1.0F
    const val FALLBACK_OPACITY = 0.62F

    fun alpha(@Suppress("UNUSED_PARAMETER") sourceAlpha: Int, opacity: Float): Int =
        (255 * opacity.coerceIn(0F, 1F)).roundToInt()
}

private class PokemonHologramBufferSource(
    private val delegate: MultiBufferSource,
) : MultiBufferSource {
    override fun getBuffer(renderType: RenderType): VertexConsumer =
        ShadowPokemonHologramShader.buffer(delegate, renderType) {
            PokemonHologramVertexConsumer(
                delegate.getBuffer(renderType),
                ShadowPokemonHologramAppearance.FALLBACK_OPACITY,
            )
        }
}

private class PokemonHologramVertexConsumer(
    private val delegate: VertexConsumer,
    private val opacity: Float,
) : VertexConsumer {
    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        delegate.addVertex(x, y, z)
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        delegate.setColor(
            (red * ShadowPokemonHologramAppearance.FALLBACK_RED).roundToInt().coerceIn(0, 255),
            (green * ShadowPokemonHologramAppearance.FALLBACK_GREEN).roundToInt().coerceIn(0, 255),
            (blue * ShadowPokemonHologramAppearance.FALLBACK_BLUE).roundToInt().coerceIn(0, 255),
            ShadowPokemonHologramAppearance.alpha(alpha, opacity),
        )
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer {
        delegate.setUv(u, v)
        return this
    }

    override fun setUv1(u: Int, v: Int): VertexConsumer {
        delegate.setUv1(u, v)
        return this
    }

    override fun setUv2(u: Int, v: Int): VertexConsumer {
        delegate.setUv2(u, v)
        return this
    }

    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer {
        delegate.setNormal(x, y, z)
        return this
    }
}
