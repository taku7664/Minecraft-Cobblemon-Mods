package jbro.cobblemon.uikit

import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

enum class UiCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT
}

data class UiHorizontalSpan(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0) { "Horizontal span start must not be negative" }
        require(endExclusive >= start) { "Horizontal span end must not precede start" }
    }
}

sealed interface UiShape {
    data object Rectangle : UiShape
    data object Circle : UiShape
    data object Capsule : UiShape
    data object Diamond : UiShape

    data class RoundedRectangle(val radiusPixels: Int) : UiShape {
        init {
            require(radiusPixels > 0) { "Rounded rectangle radius must be positive" }
        }
    }

    class Chamfer(
        val cutPixels: Int,
        corners: Set<UiCorner> = UiCorner.entries.toSet()
    ) : UiShape {
        val corners: Set<UiCorner> = corners.toSet()

        init {
            require(cutPixels > 0) { "Chamfer cut must be positive" }
            require(corners.isNotEmpty()) { "Chamfer must select at least one corner" }
        }

        override fun equals(other: Any?): Boolean =
            other is Chamfer && cutPixels == other.cutPixels && corners == other.corners

        override fun hashCode(): Int = 31 * cutPixels + corners.hashCode()

        override fun toString(): String = "Chamfer(cutPixels=$cutPixels, corners=$corners)"
    }
}

fun UiShape.horizontalSpan(width: Int, height: Int, row: Int): UiHorizontalSpan {
    require(width > 0 && height > 0) { "Surface dimensions must be positive" }
    require(row in 0 until height) { "Surface row is outside its height" }
    return when (this) {
        UiShape.Rectangle -> UiHorizontalSpan(0, width)
        UiShape.Circle -> ellipseSpan(width, height, row)
        UiShape.Capsule -> roundedSpan(width, height, row, min(width, height) / 2.0)
        UiShape.Diamond -> diamondSpan(width, height, row)
        is UiShape.RoundedRectangle -> roundedSpan(
            width,
            height,
            row,
            min(radiusPixels.toDouble(), min(width, height) / 2.0)
        )
        is UiShape.Chamfer -> {
            val cut = min(cutPixels, min((width - 1) / 2, (height - 1) / 2))
            if (cut == 0) return UiHorizontalSpan(0, width)

            val bottomDistance = height - row - 1
            val leftInset = maxOf(
                cornerInset(row, cut, UiCorner.TOP_LEFT),
                cornerInset(bottomDistance, cut, UiCorner.BOTTOM_LEFT)
            )
            val rightInset = maxOf(
                cornerInset(row, cut, UiCorner.TOP_RIGHT),
                cornerInset(bottomDistance, cut, UiCorner.BOTTOM_RIGHT)
            )
            UiHorizontalSpan(leftInset, width - rightInset)
        }
    }
}

private fun ellipseSpan(width: Int, height: Int, row: Int): UiHorizontalSpan {
    val radiusX = width / 2.0
    val radiusY = height / 2.0
    val centerY = (height - 1) / 2.0
    val normalizedY = ((row - centerY) / radiusY).coerceIn(-1.0, 1.0)
    val halfWidth = radiusX * sqrt(1.0 - normalizedY * normalizedY)
    return centeredSpan(width, halfWidth)
}

private fun roundedSpan(width: Int, height: Int, row: Int, radius: Double): UiHorizontalSpan {
    if (radius <= 0.0) return UiHorizontalSpan(0, width)
    val edgeDistance = min(row + 0.5, height - row - 0.5)
    if (edgeDistance >= radius) return UiHorizontalSpan(0, width)
    val distanceFromCenter = radius - edgeDistance
    val cornerWidth = sqrt((radius * radius - distanceFromCenter * distanceFromCenter).coerceAtLeast(0.0))
    val inset = ceil(radius - cornerWidth).toInt().coerceIn(0, width / 2)
    return UiHorizontalSpan(inset, width - inset)
}

private fun diamondSpan(width: Int, height: Int, row: Int): UiHorizontalSpan {
    val radiusY = height / 2.0
    val centerY = (height - 1) / 2.0
    val normalizedDistance = (kotlin.math.abs(row - centerY) / radiusY).coerceIn(0.0, 1.0)
    val halfWidth = width / 2.0 * (1.0 - normalizedDistance)
    return centeredSpan(width, halfWidth.coerceAtLeast(0.5))
}

private fun centeredSpan(width: Int, halfWidth: Double): UiHorizontalSpan {
    val centerX = width / 2.0
    val start = ceil(centerX - halfWidth).toInt().coerceIn(0, width - 1)
    val end = floor(centerX + halfWidth).toInt().coerceIn(start + 1, width)
    return UiHorizontalSpan(start, end)
}

private fun UiShape.Chamfer.cornerInset(distance: Int, cut: Int, corner: UiCorner): Int =
    if (corner in corners && distance < cut) cut - distance else 0

sealed interface UiFill {
    data object None : UiFill
    data class Solid(val color: Int) : UiFill
    data class VerticalGradient(val topColor: Int, val bottomColor: Int) : UiFill
}

sealed interface UiBorder {
    data object None : UiBorder

    data class Solid(val color: Int, val width: Int = 1) : UiBorder {
        init {
            require(width > 0) { "Border width must be positive" }
        }
    }

    data class PixelFrame(
        val outerColor: Int,
        val highlightColor: Int,
        val shadeColor: Int,
        val shadowColor: Int,
        val shadowOffset: Int = 1
    ) : UiBorder {
        init {
            require(shadowOffset >= 0) { "Pixel frame shadow offset must not be negative" }
        }
    }
}

data class UiSurfaceStyle(
    val shape: UiShape,
    val fill: UiFill,
    val border: UiBorder,
    val backgroundOpacity: Float = 1f
) {
    init {
        require(backgroundOpacity in 0f..1f) { "Background opacity must be between 0 and 1" }
    }

    fun resolve(overrides: UiSurfaceOverrides): UiSurfaceStyle = copy(
        shape = overrides.shape ?: shape,
        fill = overrides.fill ?: fill,
        border = overrides.border ?: border,
        backgroundOpacity = overrides.backgroundOpacity ?: backgroundOpacity
    )
}

data class UiSurfaceOverrides(
    val shape: UiShape? = null,
    val fill: UiFill? = null,
    val border: UiBorder? = null,
    val backgroundOpacity: Float? = null
) {
    init {
        require(backgroundOpacity == null || backgroundOpacity in 0f..1f) {
            "Background opacity override must be between 0 and 1"
        }
    }
}
