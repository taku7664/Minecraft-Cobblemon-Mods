package jbro.cobblemon.uikit

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Anti-aliased corners for rounded and chamfered surfaces, the way Cobblemon's own sprites soften their corners
 * with in-between pixels. A corner pixel is split into [SAMPLES] x [SAMPLES] points; the share inside the shape,
 * inset by each frame layer, tells how much of that layer shows there. Coverage depends only on the corner kind,
 * its size and the layer insets, so each combination is worked out once.
 */
object UiSmoothCorners {
    const val SAMPLES = 4

    enum class Kind { SQUARE, ROUND, CUT }

    /** The corner box a shape rounds or cuts at this size, or 0 when the shape is not drawn with smooth corners. */
    fun cornerSize(shape: UiShape, width: Int, height: Int): Int = when (shape) {
        is UiShape.RoundedRectangle -> min(shape.radiusPixels, min(width, height) / 2)
        is UiShape.Chamfer -> min(shape.cutPixels, min((width - 1) / 2, (height - 1) / 2))
        else -> 0
    }

    fun kind(shape: UiShape, corner: UiCorner): Kind = when (shape) {
        is UiShape.RoundedRectangle -> Kind.ROUND
        is UiShape.Chamfer -> if (corner in shape.corners) Kind.CUT else Kind.SQUARE
        else -> Kind.SQUARE
    }

    /**
     * For a top-left corner box of [size] pixels, the coverage of the shape inset by each of [insets]: one array per
     * inset, row-major, each value in 0..1. Other corners mirror it.
     */
    fun coverage(kind: Kind, size: Int, insets: List<Int>): List<FloatArray> {
        require(size >= 0)
        require(insets.all { it >= 0 })
        return cache.getOrPut(Key(kind, size, insets)) {
            insets.map { inset ->
                FloatArray(size * size) { index -> pixelCoverage(kind, size, inset, index % size, index / size) }
            }
        }
    }

    /** The share of pixel ([px], [py]) in a top-left corner box of [size] that lies inside the shape inset by [inset]. */
    fun pixelCoverage(kind: Kind, size: Int, inset: Int, px: Int, py: Int): Float {
        var inside = 0
        for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
            val u = px + (sx + 0.5) / SAMPLES
            val v = py + (sy + 0.5) / SAMPLES
            if (contains(kind, size, inset, u, v)) inside++
        }
        return inside.toFloat() / (SAMPLES * SAMPLES)
    }

    /** Whether the corner-local point ([u], [v]), measured from the outer corner, is inside the shape inset by [inset]. */
    private fun contains(kind: Kind, size: Int, inset: Int, u: Double, v: Double): Boolean {
        if (u < inset || v < inset) return false
        return when (kind) {
            Kind.SQUARE -> true
            Kind.ROUND -> {
                val radius = size - inset
                if (radius <= 0 || u >= size || v >= size) true
                else {
                    val du = u - size
                    val dv = v - size
                    du * du + dv * dv <= radius.toDouble() * radius
                }
            }
            // The cut edge u + v = size moved inward by the inset along its normal.
            Kind.CUT -> u + v >= size + inset * SQRT_2
        }
    }

    private data class Key(val kind: Kind, val size: Int, val insets: List<Int>)

    private val cache = ConcurrentHashMap<Key, List<FloatArray>>()
    private val SQRT_2 = sqrt(2.0)
}
