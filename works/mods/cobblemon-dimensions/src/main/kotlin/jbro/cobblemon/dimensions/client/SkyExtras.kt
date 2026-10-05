package jbro.cobblemon.dimensions.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f

/** Things drawn on a dimension's sky after the vanilla sky, called from `LevelRendererSkyMixin`. */
object SkyExtras {
    /**
     * One aurora curtain over the northern sky: where its foot sits (degrees above the horizon), how tall it hangs,
     * how wide it spreads either side of north, and a phase so the curtains do not wave together.
     */
    private class Curtain(val foot: Float, val height: Float, val spread: Float, val phase: Float, val alpha: Float)

    private val CURTAINS = listOf(
        Curtain(12f, 26f, 85f, 0f, 0.85f),
        Curtain(20f, 22f, 70f, 2.1f, 0.65f),
        Curtain(7f, 18f, 60f, 4.3f, 0.55f),
    )

    // Green-cyan at the foot, cyan through the body, violet fading out at the top.
    private val FOOT = floatArrayOf(0.35f, 1f, 0.72f)
    private val BODY = floatArrayOf(0.4f, 0.9f, 1f)
    private val TOP = floatArrayOf(0.75f, 0.5f, 1f)

    fun render(level: ClientLevel, look: DimensionLook, frustum: Matrix4f, partialTick: Float) {
        if (!look.aurora) return
        val night = 1f - (cos(level.getTimeOfDay(partialTick) * 2 * PI.toFloat()) * 2f + 0.5f).coerceIn(0f, 1f)
        // Plain by day, bright at night, gone in rain.
        val strength = (1f - level.getRainLevel(partialTick)) * (0.9f + 0.1f * night)
        if (strength <= 0f) return
        val t = level.gameTime + partialTick

        RenderSystem.enableBlend()
        // Ordinary blending by day keeps the colors against a bright sky; adding light only glows at night.
        if (night > 0.5f) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO)
        } else {
            RenderSystem.defaultBlendFunc()
        }
        RenderSystem.depthMask(false)
        // Seen from inside the sky sphere; draw both faces rather than reason about winding.
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)

        val segments = 80
        for (c in CURTAINS) {
            for (i in 0 until segments) {
                val u0 = i / segments.toFloat()
                val u1 = (i + 1) / segments.toFloat()
                val s0 = sample(c, u0, t)
                val s1 = sample(c, u1, t)
                val a0 = c.alpha * strength * s0.light
                val a1 = c.alpha * strength * s1.light
                // A soft foot, then the bright lower curtain, then a long fade to nothing at the top.
                strip(buffer, frustum, s0, s1, -1.5f, 0f, FOOT, FOOT, 0f, 0f, a0, a1)
                strip(buffer, frustum, s0, s1, 0f, 0.4f, FOOT, BODY, a0, a1, a0 * 0.7f, a1 * 0.7f)
                strip(buffer, frustum, s0, s1, 0.4f, 1f, BODY, TOP, a0 * 0.7f, a1 * 0.7f, 0f, 0f)
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    /** One column of a curtain: its direction (degrees from north), its foot and height, and how bright it is. */
    private class Column(val azimuth: Float, val foot: Float, val height: Float, val light: Float)

    private fun sample(c: Curtain, u: Float, t: Float): Column {
        val along = (u * 2f - 1f) * c.spread
        // The curtain folds and drifts slowly; its rays shimmer faster.
        val azimuth = along + 7f * sin(u * 7.5f + t * 0.012f + c.phase)
        val foot = c.foot + 3f * sin(u * 5.3f + t * 0.009f + c.phase * 1.7f)
        val height = c.height * (0.7f + 0.3f * sin(u * 9.1f - t * 0.015f + c.phase))
        val rays = 0.55f + 0.45f * sin(u * 70f + t * 0.05f + c.phase).pow(2)
        val ends = sin(u * PI.toFloat()).coerceAtLeast(0f).pow(0.7f)
        return Column(azimuth, foot, height, rays * ends)
    }

    private fun strip(buffer: BufferBuilder, m: Matrix4f, s0: Column, s1: Column, from: Float, to: Float,
                      lower: FloatArray, upper: FloatArray, a0: Float, a1: Float, b0: Float, b1: Float) {
        // [from] and [to] are fractions of the height; a negative one reaches below the foot, in degrees.
        fun elevation(s: Column, f: Float) = if (f < 0f) s.foot + f else s.foot + s.height * f
        vertex(buffer, m, s0.azimuth, elevation(s0, from), lower, a0)
        vertex(buffer, m, s1.azimuth, elevation(s1, from), lower, a1)
        vertex(buffer, m, s1.azimuth, elevation(s1, to), upper, b1)
        vertex(buffer, m, s0.azimuth, elevation(s0, to), upper, b0)
    }

    /** A point on the sky sphere (100 out), [azimuth] degrees east of north and [elevation] above the horizon. */
    private fun vertex(buffer: BufferBuilder, m: Matrix4f, azimuth: Float, elevation: Float, color: FloatArray, a: Float) {
        val az = Math.toRadians(azimuth.toDouble()).toFloat()
        val el = Math.toRadians(elevation.toDouble()).toFloat()
        buffer.addVertex(m, cos(el) * sin(az) * 100f, sin(el) * 100f, -cos(el) * cos(az) * 100f)
            .setColor(color[0], color[1], color[2], a)
    }
}
