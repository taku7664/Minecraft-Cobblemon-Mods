package jbro.cobblemon.ui.extended

import com.mojang.blaze3d.vertex.VertexConsumer
import jbro.cobblemon.ui.extended.transition.FrontFacingQuads
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrontFacingQuadsTest {
    private class Recorder : VertexConsumer {
        val points = mutableListOf<Pair<Float, Float>>()
        val colors = mutableListOf<Int>()
        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = apply { points += x to y }
        override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer =
            apply { colors += (alpha shl 24) or (red shl 16) or (green shl 8) or blue }
        override fun setUv(u: Float, v: Float): VertexConsumer = this
        override fun setUv1(u: Int, v: Int): VertexConsumer = this
        override fun setUv2(u: Int, v: Int): VertexConsumer = this
        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this
    }

    private fun area(points: List<Pair<Float, Float>>): Float =
        points.indices.sumOf { i ->
            val (x1, y1) = points[i]
            val (x2, y2) = points[(i + 1) % points.size]
            (x1 * y2 - x2 * y1).toDouble()
        }.toFloat()

    @Test
    fun `quads in either turn come out in fill's turn with their colours`() {
        val recorder = Recorder()
        val quads = FrontFacingQuads(recorder)
        // fill's own turn, then the opposite turn, then a triangle in the opposite turn.
        listOf(listOf(0f to 0f, 0f to 1f, 1f to 1f, 1f to 0f), listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f),
            listOf(0f to 0f, 1f to 0f, 1f to 1f, 1f to 1f)).forEachIndexed { index, quad ->
            quad.forEach { (x, y) -> quads.addVertex(x, y, 0f).setColor(0x10203000 or index) }
        }
        quads.finish()
        assertEquals(12, recorder.points.size)
        for (quad in recorder.points.chunked(4)) assertTrue(area(quad) < 0f, "quad $quad faces away")
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2), recorder.colors.map { it and 0xFF })
    }
}
