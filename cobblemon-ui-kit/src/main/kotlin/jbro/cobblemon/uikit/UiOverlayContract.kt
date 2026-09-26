package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component

enum class UiOverlayTone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

data class UiTooltipSpec(
    val title: Component,
    val body: Component? = null,
    val tone: UiOverlayTone = UiOverlayTone.NEUTRAL
) {
    init {
        require(title.string.isNotBlank()) { "Tooltip title must not be blank" }
        require(body == null || body.string.isNotBlank()) { "Tooltip body must not be blank" }
    }
}

data class UiDialogSpec(
    val title: Component,
    val message: Component,
    val confirmLabel: Component,
    val cancelLabel: Component? = null,
    val tone: UiOverlayTone = UiOverlayTone.NEUTRAL
) {
    init {
        require(title.string.isNotBlank()) { "Dialog title must not be blank" }
        require(message.string.isNotBlank()) { "Dialog message must not be blank" }
        require(confirmLabel.string.isNotBlank()) { "Dialog confirm label must not be blank" }
        require(cancelLabel == null || cancelLabel.string.isNotBlank()) { "Dialog cancel label must not be blank" }
    }
}

data class UiToastSpec(
    val message: Component,
    val tone: UiOverlayTone = UiOverlayTone.NEUTRAL,
    val durationMillis: Long = 2_500
) {
    init {
        require(message.string.isNotBlank()) { "Toast message must not be blank" }
        require(durationMillis > 0) { "Toast duration must be positive" }
    }
}

data class UiActiveToast(val spec: UiToastSpec, val expiresAtMillis: Long) {
    val message: Component get() = spec.message
}

class UiToastQueue {
    private val entries = ArrayDeque<UiActiveToast>()

    fun push(spec: UiToastSpec, nowMillis: Long) {
        entries += UiActiveToast(spec, nowMillis + spec.durationMillis)
    }

    fun active(nowMillis: Long): UiActiveToast? {
        while (entries.firstOrNull()?.expiresAtMillis?.let { it <= nowMillis } == true) entries.removeFirst()
        return entries.firstOrNull()
    }

    fun dismiss() {
        if (entries.isNotEmpty()) entries.removeFirst()
    }
}

object UiOverlayPlacement {
    fun place(anchor: UiRect, overlay: UiSize, screen: UiSize, margin: Int = 4): UiRect {
        require(margin >= 0) { "Overlay margin must not be negative" }
        val maxX = (screen.width - margin - overlay.width).coerceAtLeast(margin)
        val x = anchor.x.coerceIn(margin, maxX)
        val below = anchor.bottom + margin
        val y = if (below + overlay.height <= screen.height - margin) {
            below
        } else {
            (anchor.y - margin - overlay.height).coerceAtLeast(margin)
        }
        return UiRect(x, y, overlay.width, overlay.height)
    }
}
