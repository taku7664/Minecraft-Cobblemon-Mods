@file:Suppress("DEPRECATION")

package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.HoloBattleTerminalBlockEntity
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.HoloBattleTerminalContent
import jbro.cobblemon.morebattlecontent.internal.terminal.HoloTerminalAnimation
import jbro.cobblemon.morebattlecontent.internal.terminal.HoloTerminalAnimationFrame
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRenderer
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

internal object HoloBattleTerminalClientContent {
    fun register() {
        BlockEntityRenderers.register(HoloBattleTerminalContent.blockEntityType) {
            HoloBattleTerminalBlockEntityRenderer()
        }
        BuiltinItemRendererRegistry.INSTANCE.register(
            HoloBattleTerminalContent.item,
            HoloBattleTerminalItemRenderer(),
        )
    }
}

private class HoloBattleTerminalBlockEntityRenderer : BlockEntityRenderer<HoloBattleTerminalBlockEntity> {
    override fun render(
        terminal: HoloBattleTerminalBlockEntity,
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
        HoloBattleTerminalGeometry.render(poseStack, buffers, frame)
        poseStack.popPose()
    }

    override fun shouldRenderOffScreen(blockEntity: HoloBattleTerminalBlockEntity): Boolean = true

    override fun getViewDistance(): Int = 64
}

private class HoloBattleTerminalItemRenderer : BuiltinItemRenderer {
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
        HoloBattleTerminalGeometry.render(poseStack, buffers, HoloTerminalAnimation.frame(0, 0.0f))
        poseStack.popPose()
    }
}

private object HoloBattleTerminalGeometry {
    fun render(
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        frame: HoloTerminalAnimationFrame,
    ) {
        val vertices = buffers.getBuffer(RenderType.lightning())
        val matrix = poseStack.last().pose()
        cuboid(vertices, matrix, -0.43f, 0.02f, -0.43f, 0.43f, 0.16f, 0.43f, 18, 78, 96, 220)
        cuboid(vertices, matrix, -0.29f, 0.16f, -0.29f, 0.29f, 0.28f, 0.29f, 26, 148, 168, 190)
        ring(vertices, matrix, 0.38f, 0.035f, 0.42f, frame.rotationRadians, 44, 226, 238, 130)
        ring(vertices, matrix, 0.28f, 0.022f, 0.86f, -frame.rotationRadians * 1.3f, 114, 245, 255, 170)
        diamond(vertices, matrix, 0.23f + frame.pulse * 0.035f, 0.91f, frame.rotationRadians)
        scanPlane(vertices, matrix, frame.scanHeight)
    }

    private fun ring(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        radius: Float,
        halfWidth: Float,
        y: Float,
        rotation: Float,
        red: Int,
        green: Int,
        blue: Int,
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
                red,
                green,
                blue,
                alpha,
            )
        }
    }

    private fun diamond(vertices: VertexConsumer, matrix: Matrix4f, radius: Float, centerY: Float, rotation: Float) {
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
            105,
            244,
            255,
            178,
        )
        quad(
            vertices,
            matrix,
            point(0.0f, centerY + radius, 0.0f),
            point(perpendicularX, centerY, perpendicularZ),
            point(0.0f, centerY - radius, 0.0f),
            point(-perpendicularX, centerY, -perpendicularZ),
            58,
            190,
            230,
            150,
        )
    }

    private fun scanPlane(vertices: VertexConsumer, matrix: Matrix4f, y: Float) {
        quad(
            vertices,
            matrix,
            point(-0.32f, y, -0.32f),
            point(0.32f, y, -0.32f),
            point(0.32f, y, 0.32f),
            point(-0.32f, y, 0.32f),
            80,
            232,
            248,
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
        red: Int,
        green: Int,
        blue: Int,
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
        quad(vertices, matrix, p000, p100, p110, p010, red, green, blue, alpha)
        quad(vertices, matrix, p101, p001, p011, p111, red, green, blue, alpha)
        quad(vertices, matrix, p001, p000, p010, p011, red, green, blue, alpha)
        quad(vertices, matrix, p100, p101, p111, p110, red, green, blue, alpha)
        quad(vertices, matrix, p010, p110, p111, p011, red, green, blue, alpha)
        quad(vertices, matrix, p001, p101, p100, p000, red, green, blue, alpha)
    }

    private fun quad(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        first: Point,
        second: Point,
        third: Point,
        fourth: Point,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        vertex(vertices, matrix, first, red, green, blue, alpha)
        vertex(vertices, matrix, second, red, green, blue, alpha)
        vertex(vertices, matrix, third, red, green, blue, alpha)
        vertex(vertices, matrix, fourth, red, green, blue, alpha)
    }

    private fun vertex(
        vertices: VertexConsumer,
        matrix: Matrix4f,
        point: Point,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        vertices.addVertex(matrix, point.x, point.y, point.z).setColor(red, green, blue, alpha)
    }

    private fun point(x: Number, y: Number, z: Number) = Point(x.toFloat(), y.toFloat(), z.toFloat())

    private data class Point(val x: Float, val y: Float, val z: Float)

    private const val RING_SEGMENTS = 20
    private const val FULL_TURN = (Math.PI * 2.0).toFloat()
}
