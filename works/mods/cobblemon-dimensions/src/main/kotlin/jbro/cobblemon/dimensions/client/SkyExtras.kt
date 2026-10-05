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
import kotlin.math.sin
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f

/** Things drawn on a dimension's sky after the vanilla sky, called from `LevelRendererSkyMixin`. */
object SkyExtras {
    /** One band of the ring: offset from its middle line and width (in sky units, the sky sphere is 100 out), color. */
    private class Band(val offset: Float, val width: Float, val color: Int, val alpha: Float)

    private val BANDS = listOf(
        Band(-6f, 6f, 0x6FE8FF, 0.22f),
        Band(0f, 3.5f, 0xE6F8FF, 0.28f),
        Band(5f, 5f, 0xA88CFF, 0.2f),
    )

    /** How far the ring's plane leans from the horizon; 90 would stand it straight up through the zenith. */
    private const val TILT = 50.0

    fun render(level: ClientLevel, look: DimensionLook, frustum: Matrix4f, partialTick: Float) {
        if (!look.ring) return
        // Fainter in rain, a little brighter at night.
        val night = 1f - (cos(level.getTimeOfDay(partialTick) * 2 * PI.toFloat()) * 2f + 0.5f).coerceIn(0f, 1f)
        val strength = (1f - level.getRainLevel(partialTick)) * (0.75f + 0.35f * night)
        if (strength <= 0f) return

        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO)
        RenderSystem.depthMask(false)
        // Seen from inside the sky sphere; draw both faces rather than reason about winding.
        RenderSystem.disableCull()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)

        // The ring is a great circle on the sky sphere in a plane leaning TILT degrees; bands sit beside it.
        val tilt = Math.toRadians(TILT).toFloat()
        val ny = cos(tilt)
        val nz = -sin(tilt)
        val segments = 96
        for (band in BANDS) {
            val r = ((band.color shr 16) and 0xFF) / 255f
            val g = ((band.color shr 8) and 0xFF) / 255f
            val b = (band.color and 0xFF) / 255f
            for (i in 0 until segments) {
                val a0 = i * 2 * PI.toFloat() / segments
                val a1 = (i + 1) * 2 * PI.toFloat() / segments
                // Fade the ring out where it nears the horizon at either end, so it rises out of the haze.
                val f0 = edgeFade(a0)
                val f1 = edgeFade(a1)
                val alpha0 = band.alpha * strength * f0
                val alpha1 = band.alpha * strength * f1
                // Two strips per band, clear at its edges and brightest down its middle, so it reads as glow, not paint.
                for (edge in listOf(band.offset - band.width / 2, band.offset + band.width / 2)) {
                    corner(buffer, frustum, a0, edge, ny, nz, tilt, r, g, b, 0f)
                    corner(buffer, frustum, a1, edge, ny, nz, tilt, r, g, b, 0f)
                    corner(buffer, frustum, a1, band.offset, ny, nz, tilt, r, g, b, alpha1)
                    corner(buffer, frustum, a0, band.offset, ny, nz, tilt, r, g, b, alpha0)
                }
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    private fun edgeFade(angle: Float): Float = (sin(angle) * 3f).coerceIn(0f, 1f)

    private fun corner(buffer: BufferBuilder, m: Matrix4f, angle: Float, offset: Float,
                       ny: Float, nz: Float, tilt: Float, r: Float, g: Float, b: Float, a: Float) {
        val x = cos(angle) * 100f
        val y = sin(angle) * sin(tilt) * 100f + ny * offset
        val z = sin(angle) * cos(tilt) * 100f + nz * offset
        buffer.addVertex(m, x, y, z).setColor(r, g, b, a)
    }
}
