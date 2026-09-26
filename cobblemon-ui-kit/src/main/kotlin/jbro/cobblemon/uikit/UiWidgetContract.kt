package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import kotlin.math.max

data class UiTabSpec(
    val label: Component,
    val selected: Boolean = false,
    val icon: UiIcon? = null,
    val width: UiWidthPolicy = UiWidthPolicy.Content
) {
    init {
        require(label.string.isNotBlank()) { "Tab label must not be blank" }
    }
}

data class UiListItemSpec(
    val title: Component,
    val supportingText: Component? = null,
    val icon: UiIcon? = null,
    val trailingText: Component? = null,
    val selected: Boolean = false,
    val width: UiWidthPolicy = UiWidthPolicy.Fill
) {
    init {
        require(title.string.isNotBlank()) { "List item title must not be blank" }
    }
}

enum class UiBadgeTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    DANGER
}

data class UiBadgeSpec(
    val label: Component,
    val tone: UiBadgeTone = UiBadgeTone.NEUTRAL,
    val icon: UiIcon? = null
) {
    init {
        require(label.string.isNotBlank()) { "Badge label must not be blank" }
    }
}

data class UiToggleSpec(
    val label: Component,
    val value: Boolean,
    val size: UiControlSize = UiControlSize.MEDIUM,
    val width: UiWidthPolicy = UiWidthPolicy.Content
) {
    init {
        require(label.string.isNotBlank()) { "Toggle label must not be blank" }
    }
}

data class UiProgressSpec(
    val value: Int,
    val maximum: Int,
    val label: Component? = null,
    val showValue: Boolean = false
) {
    init {
        require(maximum > 0) { "Progress maximum must be positive" }
    }

    val fraction: Float = (value.toFloat() / maximum).coerceIn(0f, 1f)
}

data class UiVerticalRange(val start: Int, val endExclusive: Int) {
    init {
        require(endExclusive >= start) { "Vertical range end must not precede start" }
    }
}

class UiScrollState(
    val viewportHeight: Int,
    val contentHeight: Int,
    val step: Int = 18
) {
    init {
        require(viewportHeight > 0) { "Scroll viewport height must be positive" }
        require(contentHeight >= 0) { "Scroll content height must not be negative" }
        require(step > 0) { "Scroll step must be positive" }
    }

    val maxOffset: Int = max(0, contentHeight - viewportHeight)
    var offset: Int = 0
        private set

    fun scroll(delta: Double): Boolean {
        if (delta == 0.0) return false
        val direction = if (delta > 0.0) -1 else 1
        return jumpTo(offset + direction * step)
    }

    fun jumpTo(requestedOffset: Int): Boolean {
        val next = requestedOffset.coerceIn(0, maxOffset)
        if (next == offset) return false
        offset = next
        return true
    }

    fun thumb(trackTop: Int, trackHeight: Int, minimumHeight: Int = 8): UiVerticalRange {
        require(trackHeight > 0) { "Scroll track height must be positive" }
        require(minimumHeight > 0) { "Scroll thumb minimum height must be positive" }
        val thumbHeight = if (contentHeight <= viewportHeight || contentHeight == 0) {
            trackHeight
        } else {
            max(minimumHeight, trackHeight * viewportHeight / contentHeight).coerceAtMost(trackHeight)
        }
        val travel = trackHeight - thumbHeight
        val thumbTop = trackTop + if (maxOffset == 0) 0 else travel * offset / maxOffset
        return UiVerticalRange(thumbTop, thumbTop + thumbHeight)
    }
}
