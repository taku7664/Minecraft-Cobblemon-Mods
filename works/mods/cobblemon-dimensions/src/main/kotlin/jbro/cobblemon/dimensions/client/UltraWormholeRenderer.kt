package jbro.cobblemon.dimensions.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import java.awt.Color
import jbro.cobblemon.dimensions.wormhole.UltraWormhole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f

/**
 * Draws an Ultra Wormhole facing the camera, after the games' rifts: a white-hot core, a rim whose colors run through
 * the rainbow and whose edge flickers, spiral arms of energy turning inside, and halo rings around the larger holes.
 * Everything blends additively, so it glows against the sky. No textures.
 */
class UltraWormholeRenderer(context: EntityRendererProvider.Context) : EntityRenderer<UltraWormhole>(context) {
    override fun getTextureLocation(entity: UltraWormhole): ResourceLocation = ResourceLocation.withDefaultNamespace("textures/misc/white.png")

    override fun render(hole: UltraWormhole, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val t = hole.tickCount + partialTick
        // Opens over two seconds and closes over the last two.
        val open = (min(t, hole.lifetime - t) / 40f).coerceIn(0f, 1f)
        if (open <= 0f) return
        val radius = hole.radius * (0.2f + 0.8f * open)
        pose.pushPose()
        pose.translate(0.0, hole.bbHeight / 2.0, 0.0)
        pose.mulPose(entityRenderDispatcher.cameraOrientation())
        val matrix = pose.last().pose()
        val out = buffers.getBuffer(RenderType.lightning())

        val segments = 48
        val hueShift = t * 0.004f
        // Rim: rainbow, its outer edge flickering like a tear in the sky.
        for (i in 0 until segments) {
            val a0 = i * 2 * PI.toFloat() / segments
            val a1 = (i + 1) * 2 * PI.toFloat() / segments
            val inner = radius * 0.42f
            val outer0 = radius * (1f + 0.10f * sin(a0 * 7 + t * 0.21f) + 0.05f * sin(a0 * 13 - t * 0.33f))
            val outer1 = radius * (1f + 0.10f * sin(a1 * 7 + t * 0.21f) + 0.05f * sin(a1 * 13 - t * 0.33f))
            val c0 = rainbow(a0 / (2 * PI.toFloat()) + hueShift)
            val c1 = rainbow(a1 / (2 * PI.toFloat()) + hueShift)
            quad(out, matrix,
                cos(a0) * inner, sin(a0) * inner, c0, 0.85f * open,
                cos(a1) * inner, sin(a1) * inner, c1, 0.85f * open,
                cos(a1) * outer1, sin(a1) * outer1, c1, 0f,
                cos(a0) * outer0, sin(a0) * outer0, c0, 0f)
        }
        // Core: white at the middle, pale cyan at its edge, breathing slowly.
        val core = radius * (0.42f + 0.03f * sin(t * 0.1f))
        val white = floatArrayOf(1f, 1f, 1f)
        val pale = floatArrayOf(0.75f, 0.95f, 1f)
        for (i in 0 until segments) {
            val a0 = i * 2 * PI.toFloat() / segments
            val a1 = (i + 1) * 2 * PI.toFloat() / segments
            quad(out, matrix, 0f, 0f, white, open, 0f, 0f, white, open,
                cos(a1) * core, sin(a1) * core, pale, 0.9f * open, cos(a0) * core, sin(a0) * core, pale, 0.9f * open)
        }
        // Spiral arms turning inward.
        val arms = 4
        for (arm in 0 until arms) {
            val base = arm * 2 * PI.toFloat() / arms + t * 0.06f
            val steps = 14
            for (s in 0 until steps) {
                val f0 = s / steps.toFloat()
                val f1 = (s + 1) / steps.toFloat()
                val a0 = base + f0 * 2.6f
                val a1 = base + f1 * 2.6f
                val r0 = radius * (0.08f + 0.85f * f0)
                val r1 = radius * (0.08f + 0.85f * f1)
                val w0 = radius * 0.07f * (1 - f0)
                val w1 = radius * 0.07f * (1 - f1)
                val color = rainbow(arm / arms.toFloat() + hueShift * 2)
                val alpha0 = 0.7f * open * (1 - f0)
                val alpha1 = 0.7f * open * (1 - f1)
                quad(out, matrix,
                    cos(a0) * (r0 - w0), sin(a0) * (r0 - w0), color, alpha0,
                    cos(a1) * (r1 - w1), sin(a1) * (r1 - w1), color, alpha1,
                    cos(a1) * (r1 + w1), sin(a1) * (r1 + w1), color, alpha1,
                    cos(a0) * (r0 + w0), sin(a0) * (r0 + w0), color, alpha0)
            }
        }
        // Halos: one ring for an ordinary hole, two for a great one, broken into turning dashes.
        val halos = if (hole.radius >= 4f) 2 else 1
        for (h in 1..halos) {
            val ring = radius * (1.25f + 0.3f * h)
            val width = radius * 0.05f
            val turn = t * 0.02f * (if (h % 2 == 0) -1 else 1)
            for (i in 0 until segments) {
                if (i % 4 == 3) continue
                val a0 = i * 2 * PI.toFloat() / segments + turn
                val a1 = (i + 1) * 2 * PI.toFloat() / segments + turn
                val color = rainbow(i / segments.toFloat() - hueShift)
                quad(out, matrix,
                    cos(a0) * (ring - width), sin(a0) * (ring - width), color, 0.55f * open,
                    cos(a1) * (ring - width), sin(a1) * (ring - width), color, 0.55f * open,
                    cos(a1) * (ring + width), sin(a1) * (ring + width), color, 0.55f * open,
                    cos(a0) * (ring + width), sin(a0) * (ring + width), color, 0.55f * open)
            }
        }
        pose.popPose()
        super.render(hole, yaw, partialTick, pose, buffers, light)
    }

    private fun rainbow(hue: Float): FloatArray {
        // Clear but not neon; additive blending already pales it against a bright sky.
        val rgb = Color.HSBtoRGB(((hue % 1f) + 1f) % 1f, 0.7f, 1f)
        return floatArrayOf((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f)
    }

    /** A quad drawn both ways round, so it shows whichever side the camera sees. */
    private fun quad(out: VertexConsumer, m: Matrix4f,
                     x0: Float, y0: Float, c0: FloatArray, a0: Float, x1: Float, y1: Float, c1: FloatArray, a1: Float,
                     x2: Float, y2: Float, c2: FloatArray, a2: Float, x3: Float, y3: Float, c3: FloatArray, a3: Float) {
        vertex(out, m, x0, y0, c0, a0); vertex(out, m, x1, y1, c1, a1); vertex(out, m, x2, y2, c2, a2); vertex(out, m, x3, y3, c3, a3)
        vertex(out, m, x3, y3, c3, a3); vertex(out, m, x2, y2, c2, a2); vertex(out, m, x1, y1, c1, a1); vertex(out, m, x0, y0, c0, a0)
    }

    private fun vertex(out: VertexConsumer, m: Matrix4f, x: Float, y: Float, c: FloatArray, a: Float) {
        out.addVertex(m, x, y, 0f).setColor(c[0], c[1], c[2], a)
    }
}
