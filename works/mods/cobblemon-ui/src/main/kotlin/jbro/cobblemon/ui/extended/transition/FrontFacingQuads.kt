package jbro.cobblemon.ui.extended.transition

import com.mojang.blaze3d.vertex.VertexConsumer

/**
 * Passes position-colour quads to [delegate] turned to face the screen. `RenderType.gui()` culls back faces, and a
 * quad laid out in the wrong turn (a slash, a shard, a plate on the far side) silently vanishes; this reorders each
 * quad of four vertices to the turn `GuiGraphics.fill` uses before it reaches the buffer. Call [finish] when done.
 */
internal class FrontFacingQuads(private val delegate: VertexConsumer) : VertexConsumer {
    private val xs = FloatArray(4)
    private val ys = FloatArray(4)
    private val zs = FloatArray(4)
    private val colors = IntArray(4)
    private var count = 0

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        if (count == 4) emit()
        xs[count] = x
        ys[count] = y
        zs[count] = z
        colors[count] = -1
        count++
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        if (count > 0) {
            colors[count - 1] = (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF)
        }
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer = this
    override fun setUv1(u: Int, v: Int): VertexConsumer = this
    override fun setUv2(u: Int, v: Int): VertexConsumer = this
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this

    /** Sends the last quad on. */
    fun finish() {
        if (count == 4) emit()
        count = 0
    }

    private fun emit() {
        // `fill` goes top left, bottom left, bottom right, top right: a negative shoelace sum with y pointing down.
        var area = 0f
        for (i in 0 until 4) {
            val next = (i + 1) % 4
            area += xs[i] * ys[next] - xs[next] * ys[i]
        }
        val order = if (area > 0f) intArrayOf(0, 3, 2, 1) else intArrayOf(0, 1, 2, 3)
        for (i in order) delegate.addVertex(xs[i], ys[i], zs[i]).setColor(colors[i])
        count = 0
    }
}
