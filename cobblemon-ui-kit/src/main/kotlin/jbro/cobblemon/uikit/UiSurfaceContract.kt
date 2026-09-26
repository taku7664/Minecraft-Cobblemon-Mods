package jbro.cobblemon.uikit

import kotlin.math.min

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
    if (this is UiShape.Rectangle) return UiHorizontalSpan(0, width)

    this as UiShape.Chamfer
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
    return UiHorizontalSpan(leftInset, width - rightInset)
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
