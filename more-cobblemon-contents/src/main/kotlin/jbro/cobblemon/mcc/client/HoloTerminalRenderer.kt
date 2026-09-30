@file:Suppress("DEPRECATION")

package jbro.cobblemon.mcc.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.compat.fabric.HoloTerminalBlockEntity
import jbro.cobblemon.mcc.internal.terminal.HoloTerminalAnimation
import jbro.cobblemon.mcc.internal.terminal.HoloTerminalAnimationFrame
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRenderer
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/** Draws every hologram terminal; content mods register theirs while initializing, before any client starts. */
internal object HoloTerminalClientContent {
    fun register() {
        HoloTerminals.all().forEach { terminal ->
            @Suppress("UNCHECKED_CAST")
            BlockEntityRenderers.register(terminal.blockEntityType as BlockEntityType<HoloTerminalBlockEntity>) {
                HoloTerminalBlockEntityRenderer()
            }
            BuiltinItemRendererRegistry.INSTANCE.register(terminal.item, HoloTerminalItemRenderer(terminal.palette))
        }
    }
}

private class HoloTerminalBlockEntityRenderer : BlockEntityRenderer<HoloTerminalBlockEntity> {
    override fun render(
        terminal: HoloTerminalBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val gameTime = terminal.level?.gameTime ?: 0L
        val frame = HoloTerminalAnimation.frame(gameTime, partialTick.coerceIn(0.0f, 1.0f))
        poseStack.pushPose()
        poseStack.translate(0.5, 0.0, 0.5)
        HoloTerminalGeometry.render(poseStack, buffers, frame, terminal.terminal.palette)
        poseStack.popPose()
    }

    override fun shouldRenderOffScreen(blockEntity: HoloTerminalBlockEntity): Boolean = true

    override fun getViewDistance(): Int = 64
}

private class HoloTerminalItemRenderer(private val palette: HoloTerminalPalette) : BuiltinItemRenderer {
    override fun render(
        stack: ItemStack,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.0, 0.5)
        poseStack.scale(0.78f, 0.78f, 0.78f)
        HoloTerminalGeometry.render(poseStack, buffers, HoloTerminalAnimation.frame(0, 0.0f), palette)
        poseStack.popPose()
    }
}

private object HoloTerminalGeometry {
    fun render(
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        frame: HoloTerminalAnimationFrame,
        palette: HoloTerminalPalette,
    ) {
        val vertices = buffers.getBuffer(RenderType.lightning())
        val matrix = poseStack.last().pose()
        cuboid(vertices, matrix, -0.43f, 0.02f, -0.43f, 0.43f, 0.16f, 0.43f, palette.base, 220)
        cuboid(vertices, matrix, -0.29f, 0.16f, -0.29f, 0.29f, 0.28f, 0.29f, palette.pillar, 190)
        ring(vertices, matrix, 0.38f, 0.035f, 0.42f, frame.rotationRadians, palette.outerRing, 130)
        ring(vertices, matrix, 0.28f, 0.022f, 0.86f, -frame.rotationRadians * 1.3f, palette.innerRing, 170)
        diamond(vertices, matrix, 0.23f + frame.pulse * 0.035f, 0.91f, frame.rotationRadians, palette)
        scanPlane(vertices, matrix, frame.scanHeight, palette.scan)
    }

    private fun ring(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        radius: Float,
        halfWidth: Float,
        y: Float,
        rotation: Float,
        rgb: Int,
        alpha: Int,
    ) {
        repeat(RING_SEGMENTS) { segment ->
            val start = rotation + FULL_TURN * segment / RING_SEGMENTS
            val end = rotation + FULL_TURN * (segment + 1) / RING_SEGMENTS
            quad(
                vertices,
                matrix,
                point(cos(start) * (radius - halfWidth), y, sin(start) * (radius - halfWidth)),
                point(cos(start) * (radius + halfWidth), y, sin(start) * (radius + halfWidth)),
                point(cos(end) * (radius + halfWidth), y, sin(end) * (radius + halfWidth)),
                point(cos(end) * (radius - halfWidth), y, sin(end) * (radius - halfWidth)),
                rgb,
                alpha,
            )
        }
    }

    private fun diamond(vertices: VertexConsumer, matrix: Matrix4f, radius: Float, centerY: Float, rotation: Float, palette: HoloTerminalPalette) {
        val x = cos(rotation) * radius
        val z = sin(rotation) * radius
        val perpendicularX = -z
        val perpendicularZ = x
        quad(
            vertices,
            matrix,
            point(0.0f, centerY + radius, 0.0f),
            point(x, centerY, z),
            point(0.0f, centerY - radius, 0.0f),
            point(-x, centerY, -z),
            palette.crystal,
            178,
        )
        quad(
            vertices,
            matrix,
            point(0.0f, centerY + radius, 0.0f),
            point(perpendicularX, centerY, perpendicularZ),
            point(0.0f, centerY - radius, 0.0f),
            point(-perpendicularX, centerY, -perpendicularZ),
            palette.crystalEdge,
            150,
        )
    }

    private fun scanPlane(vertices: VertexConsumer, matrix: Matrix4f, y: Float, rgb: Int) {
        quad(
            vertices,
            matrix,
            point(-0.32f, y, -0.32f),
            point(0.32f, y, -0.32f),
            point(0.32f, y, 0.32f),
            point(-0.32f, y, 0.32f),
            rgb,
            42,
        )
    }

    private fun cuboid(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
        rgb: Int,
        alpha: Int,
    ) {
        val p000 = point(minX, minY, minZ)
        val p001 = point(minX, minY, maxZ)
        val p010 = point(minX, maxY, minZ)
        val p011 = point(minX, maxY, maxZ)
        val p100 = point(maxX, minY, minZ)
        val p101 = point(maxX, minY, maxZ)
        val p110 = point(maxX, maxY, minZ)
        val p111 = point(maxX, maxY, maxZ)
        quad(vertices, matrix, p000, p100, p110, p010, rgb, alpha)
        quad(vertices, matrix, p101, p001, p011, p111, rgb, alpha)
        quad(vertices, matrix, p001, p000, p010, p011, rgb, alpha)
        quad(vertices, matrix, p100, p101, p111, p110, rgb, alpha)
        quad(vertices, matrix, p010, p110, p111, p011, rgb, alpha)
        quad(vertices, matrix, p001, p101, p100, p000, rgb, alpha)
    }

    private fun quad(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        first: Point,
        second: Point,
        third: Point,
        fourth: Point,
        rgb: Int,
        alpha: Int,
    ) {
        vertex(vertices, matrix, first, rgb, alpha)
        vertex(vertices, matrix, second, rgb, alpha)
        vertex(vertices, matrix, third, rgb, alpha)
        vertex(vertices, matrix, fourth, rgb, alpha)
    }

    private fun vertex(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        point: Point,
        rgb: Int,
        alpha: Int,
    ) {
        vertices.addVertex(matrix, point.x, point.y, point.z).setColor(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, alpha)
    }

    private fun point(x: Number, y: Number, z: Number) = Point(x.toFloat(), y.toFloat(), z.toFloat())

    private data class Point(val x: Float, val y: Float, val z: Float)

    private const val RING_SEGMENTS = 20
    private const val FULL_TURN = (Math.PI * 2.0).toFloat()
}
