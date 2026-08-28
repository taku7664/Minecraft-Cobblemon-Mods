package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType

internal object ShadowHologramFloorGeometry {
    const val SEGMENTS = 64
    const val COLOR_RED = 97
    const val COLOR_GREEN = 79
    const val COLOR_BLUE = 19
    private const val RADIUS = 0.90F
    private const val CENTER_ALPHA = 74

    data class Vertex(val x: Float, val z: Float, val alpha: Int)

    fun vertices(): List<Vertex> = buildList(SEGMENTS + 2) {
        add(Vertex(0F, 0F, CENTER_ALPHA))
        for (index in 0..SEGMENTS) {
            val angle = 2.0 * PI * index / SEGMENTS
            add(Vertex((cos(angle) * RADIUS).toFloat(), (sin(angle) * RADIUS).toFloat(), 0))
        }
    }
}

internal object ShadowHologramFloorRenderer {
    private val renderType: RenderType by lazy {
        RenderType.create(
            "mbc_shadow_hologram_floor",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLE_FAN,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .createCompositeState(false),
        )
    }

    fun render(
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        x: Double,
        y: Double,
        z: Double,
    ) {
        poseStack.pushPose()
        poseStack.translate(x, y + FLOOR_OFFSET, z)
        try {
            val pose = poseStack.last().pose()
            val consumer = buffers.getBuffer(renderType)
            ShadowHologramFloorGeometry.vertices().forEach { vertex ->
                consumer.addVertex(pose, vertex.x, 0F, vertex.z)
                    .setColor(
                        ShadowHologramFloorGeometry.COLOR_RED,
                        ShadowHologramFloorGeometry.COLOR_GREEN,
                        ShadowHologramFloorGeometry.COLOR_BLUE,
                        vertex.alpha,
                    )
            }
        } finally {
            poseStack.popPose()
        }
    }

    private const val FLOOR_OFFSET = 0.0125
}
