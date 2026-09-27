package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import kotlin.math.min

enum class UiTextAlignment { START, CENTER, END }
enum class UiTextTone { PRIMARY, SECONDARY, MUTED, SUCCESS, WARNING, DANGER, PANEL, PANEL_ALT }

data class UiTextSpec(
    val text: Component,
    val tone: UiTextTone = UiTextTone.PRIMARY,
    val alignment: UiTextAlignment = UiTextAlignment.START,
    val maxLines: Int = Int.MAX_VALUE,
    val lineSpacing: Int = 1,
    val ellipsis: Boolean = true,
    val shadow: Boolean = false
) {
    init {
        require(text.string.isNotBlank()) { "Text must not be blank" }
        require(maxLines > 0) { "Text max lines must be positive" }
        require(lineSpacing >= 0) { "Text line spacing must not be negative" }
    }
}

enum class UiPanelTone { SHELL, PANEL, RAISED }

data class UiPanelSpec(
    val title: Component? = null,
    val tone: UiPanelTone = UiPanelTone.PANEL,
    val padding: UiInsets = UiInsets.all(6)
) {
    init {
        require(title == null || title.string.isNotBlank()) { "Panel title must not be blank" }
    }

    fun contentBounds(bounds: UiRect, titleHeight: Int = 0): UiRect {
        require(titleHeight >= 0) { "Panel title height must not be negative" }
        val width = (bounds.width - padding.left - padding.right).coerceAtLeast(0)
        val height = (bounds.height - padding.top - padding.bottom - titleHeight).coerceAtLeast(0)
        return UiRect(bounds.x + padding.left, bounds.y + padding.top + titleHeight, width, height)
    }
}

enum class UiStepState { LOCKED, AVAILABLE, CLEARED, ACTIVE }

data class UiStepSpec(
    val id: String,
    val label: Component,
    val state: UiStepState,
    val icon: UiIcon? = null
) {
    init {
        require(id.matches(Regex("[a-z0-9_.-]+"))) { "Step id is invalid: $id" }
        require(label.string.isNotBlank()) { "Step label must not be blank" }
    }
}

data class UiStepTrackSpec(
    val steps: List<UiStepSpec>,
    val axis: UiAxis = UiAxis.HORIZONTAL,
    val showLabels: Boolean = true
) {
    init {
        require(steps.isNotEmpty()) { "Step track must contain at least one step" }
        require(steps.map(UiStepSpec::id).distinct().size == steps.size) { "Step ids must be unique" }
    }
}

data class UiCalloutSpec(
    val tone: UiOverlayTone,
    val title: Component,
    val body: Component? = null,
    val icon: UiIcon? = null
) {
    init {
        require(title.string.isNotBlank()) { "Callout title must not be blank" }
        require(body == null || body.string.isNotBlank()) { "Callout body must not be blank" }
    }
}

data class UiRenderSlotSpec(
    val accessibleLabel: Component,
    val fallbackIcon: UiIcon? = null,
    val padding: Int = 2
) {
    init {
        require(accessibleLabel.string.isNotBlank()) { "Render slot label must not be blank" }
        require(padding >= 0) { "Render slot padding must not be negative" }
    }
}

/** How a render slot frames a player-shaped model: head and chest, or the whole body. */
enum class UiModelFraming { PORTRAIT, FULL_BODY }

/**
 * Where a player-shaped model sits inside a slot. The model origin is its neck pivot: the head extends half a
 * [scale] above [originY] and the feet reach one and a half below it.
 */
data class UiModelPlacement(val centerX: Int, val originY: Float, val scale: Int) {
    companion object {
        private const val MAX_SCALE = 120
        private const val WIDTH_USAGE = 0.88f

        /** How far a player model reaches above its neck pivot, in model units. */
        const val PLAYER_HEAD = 0.5f

        /** How far a player or villager model reaches below its neck pivot, down to the feet. */
        const val BODY = 1.5f

        /** A villager's taller head. */
        const val VILLAGER_HEAD = 0.625f

        /**
         * Places a humanoid model that reaches [head] units above its neck pivot and [body] units below it. A portrait
         * shows the head and one unit below the neck; a full body shows everything.
         */
        fun calculate(bounds: UiRect, framing: UiModelFraming, head: Float = PLAYER_HEAD, body: Float = BODY): UiModelPlacement {
            val widthScale = (bounds.width * WIDTH_USAGE).toInt()
            val centerX = bounds.x + bounds.width / 2
            return when (framing) {
                // Pinned to the top so the head stays whole while the slot crops somewhere below the chest.
                UiModelFraming.PORTRAIT -> {
                    val scale = min((bounds.height * 0.90f / (head + 1f)).toInt(), widthScale).coerceIn(1, MAX_SCALE)
                    UiModelPlacement(centerX, bounds.y + head * scale, scale)
                }
                UiModelFraming.FULL_BODY -> {
                    val span = head + body
                    val scale = min((bounds.height * 0.94f / span).toInt(), widthScale).coerceIn(1, MAX_SCALE)
                    UiModelPlacement(centerX, bounds.y + (bounds.height - scale * span) / 2f + head * scale, scale)
                }
            }
        }
    }
}

class UiOrderedSelectionState(
    val options: List<UiChoiceOption>,
    val maximumSelections: Int,
    initiallySelectedIds: List<String> = emptyList()
) {
    init {
        require(options.isNotEmpty()) { "Ordered selection options must not be empty" }
        require(options.map(UiChoiceOption::id).distinct().size == options.size) { "Ordered selection ids must be unique" }
        require(maximumSelections in 1..options.size) { "Ordered selection limit is invalid" }
        require(initiallySelectedIds.distinct().size == initiallySelectedIds.size) { "Initial selection contains duplicates" }
        require(initiallySelectedIds.size <= maximumSelections) { "Initial selection exceeds the limit" }
        val available = options.filter(UiChoiceOption::enabled).map(UiChoiceOption::id).toSet()
        require(initiallySelectedIds.all(available::contains)) { "Initial selection contains unavailable ids" }
    }

    private val orderedIds = initiallySelectedIds.toMutableList()
    val selectedIds: List<String> get() = orderedIds.toList()

    fun toggle(id: String): Boolean {
        val option = options.firstOrNull { it.id == id } ?: return false
        if (!option.enabled) return false
        val existing = orderedIds.indexOf(id)
        if (existing >= 0) {
            orderedIds.removeAt(existing)
            return true
        }
        if (orderedIds.size >= maximumSelections) return false
        orderedIds += id
        return true
    }

    fun moveEarlier(id: String): Boolean {
        val index = orderedIds.indexOf(id)
        if (index <= 0) return false
        orderedIds[index] = orderedIds[index - 1].also { orderedIds[index - 1] = id }
        return true
    }

    fun moveLater(id: String): Boolean {
        val index = orderedIds.indexOf(id)
        if (index < 0 || index >= orderedIds.lastIndex) return false
        orderedIds[index] = orderedIds[index + 1].also { orderedIds[index + 1] = id }
        return true
    }

    fun orderOf(id: String): Int? = orderedIds.indexOf(id).takeIf { it >= 0 }?.plus(1)
}
