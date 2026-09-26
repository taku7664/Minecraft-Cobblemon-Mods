package jbro.cobblemon.uikit

import kotlin.math.max

data class UiSize(val width: Int, val height: Int) {
    init {
        require(width >= 0 && height >= 0) { "UI size must not be negative" }
    }
}

data class UiRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width >= 0 && height >= 0) { "UI rectangle size must not be negative" }
    }

    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

data class UiInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(listOf(left, top, right, bottom).all { it >= 0 }) { "UI insets must not be negative" }
    }

    companion object {
        fun all(value: Int): UiInsets = UiInsets(value, value, value, value)
        val None: UiInsets = all(0)
    }
}

data class UiLayoutItem(
    val key: String,
    val width: Int,
    val height: Int,
    val weight: Int = 0
) {
    init {
        require(key.isNotBlank()) { "Layout item key must not be blank" }
        require(width >= 0 && height >= 0) { "Layout item size must not be negative" }
        require(weight >= 0) { "Layout item weight must not be negative" }
    }
}

data class UiLayoutPlacement(val key: String, val bounds: UiRect)

enum class UiAxis { HORIZONTAL, VERTICAL }
enum class UiCrossAlignment { START, CENTER, END, STRETCH }

data class UiStackLayout(
    val axis: UiAxis,
    val gap: Int = 0,
    val padding: UiInsets = UiInsets.None,
    val crossAlignment: UiCrossAlignment = UiCrossAlignment.START
) {
    init {
        require(gap >= 0) { "Stack gap must not be negative" }
    }

    fun place(containerWidth: Int, containerHeight: Int, items: List<UiLayoutItem>): List<UiLayoutPlacement> {
        require(containerWidth >= padding.left + padding.right) { "Stack width is smaller than its padding" }
        require(containerHeight >= padding.top + padding.bottom) { "Stack height is smaller than its padding" }
        if (items.isEmpty()) return emptyList()

        val innerWidth = containerWidth - padding.left - padding.right
        val innerHeight = containerHeight - padding.top - padding.bottom
        val gapTotal = gap * (items.size - 1)
        val baseMain = items.sumOf { if (axis == UiAxis.HORIZONTAL) it.width else it.height }
        val totalWeight = items.sumOf(UiLayoutItem::weight)
        val extra = max(0, (if (axis == UiAxis.HORIZONTAL) innerWidth else innerHeight) - baseMain - gapTotal)
        var remainingExtra = extra
        var remainingWeight = totalWeight
        var cursor = if (axis == UiAxis.HORIZONTAL) padding.left else padding.top

        return items.map { item ->
            val share = if (totalWeight == 0 || item.weight == 0) 0 else {
                val value = if (item.weight == remainingWeight) {
                    remainingExtra
                } else {
                    remainingExtra * item.weight / remainingWeight
                }
                remainingExtra -= value
                remainingWeight -= item.weight
                value
            }
            val mainSize = (if (axis == UiAxis.HORIZONTAL) item.width else item.height) + share
            val requestedCross = if (axis == UiAxis.HORIZONTAL) item.height else item.width
            val availableCross = if (axis == UiAxis.HORIZONTAL) innerHeight else innerWidth
            val crossSize = if (crossAlignment == UiCrossAlignment.STRETCH) availableCross else requestedCross.coerceAtMost(availableCross)
            val crossStart = when (crossAlignment) {
                UiCrossAlignment.START, UiCrossAlignment.STRETCH -> if (axis == UiAxis.HORIZONTAL) padding.top else padding.left
                UiCrossAlignment.CENTER -> (if (axis == UiAxis.HORIZONTAL) padding.top else padding.left) + (availableCross - crossSize) / 2
                UiCrossAlignment.END -> (if (axis == UiAxis.HORIZONTAL) padding.top else padding.left) + availableCross - crossSize
            }
            val bounds = if (axis == UiAxis.HORIZONTAL) {
                UiRect(cursor, crossStart, mainSize, crossSize)
            } else {
                UiRect(crossStart, cursor, crossSize, mainSize)
            }
            cursor += mainSize + gap
            UiLayoutPlacement(item.key, bounds)
        }
    }
}

data class UiFlowLayout(
    val horizontalGap: Int = 0,
    val verticalGap: Int = 0,
    val padding: UiInsets = UiInsets.None
) {
    init {
        require(horizontalGap >= 0 && verticalGap >= 0) { "Flow gaps must not be negative" }
    }

    fun place(containerWidth: Int, items: List<UiLayoutItem>): List<UiLayoutPlacement> {
        val right = containerWidth - padding.right
        require(right >= padding.left) { "Flow width is smaller than its padding" }
        var x = padding.left
        var y = padding.top
        var rowHeight = 0
        return items.map { item ->
            val itemWidth = item.width.coerceAtMost(right - padding.left)
            if (x != padding.left && x + itemWidth > right) {
                x = padding.left
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            val placement = UiLayoutPlacement(item.key, UiRect(x, y, itemWidth, item.height))
            x += itemWidth + horizontalGap
            rowHeight = max(rowHeight, item.height)
            placement
        }
    }
}

data class UiGridLayout(
    val columns: Int,
    val horizontalGap: Int = 0,
    val verticalGap: Int = 0,
    val padding: UiInsets = UiInsets.None
) {
    init {
        require(columns > 0) { "Grid columns must be positive" }
        require(horizontalGap >= 0 && verticalGap >= 0) { "Grid gaps must not be negative" }
    }

    fun place(containerWidth: Int, items: List<UiLayoutItem>): List<UiLayoutPlacement> {
        val innerWidth = containerWidth - padding.left - padding.right
        val gapTotal = horizontalGap * (columns - 1)
        require(innerWidth >= gapTotal) { "Grid width is smaller than its gaps and padding" }
        val columnWidth = (innerWidth - gapTotal) / columns
        val result = mutableListOf<UiLayoutPlacement>()
        var rowTop = padding.top
        items.chunked(columns).forEach { row ->
            val rowHeight = row.maxOfOrNull(UiLayoutItem::height) ?: 0
            row.forEachIndexed { column, item ->
                val x = padding.left + column * (columnWidth + horizontalGap)
                result += UiLayoutPlacement(item.key, UiRect(x, rowTop, columnWidth, item.height))
            }
            rowTop += rowHeight + verticalGap
        }
        return result
    }
}

enum class UiAnchor {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT
}

data class UiAnchoredItem(
    val key: String,
    val width: Int,
    val height: Int,
    val anchor: UiAnchor,
    val offsetX: Int = 0,
    val offsetY: Int = 0
) {
    init {
        require(key.isNotBlank()) { "Anchored item key must not be blank" }
        require(width >= 0 && height >= 0) { "Anchored item size must not be negative" }
    }
}

data class UiAnchorLayout(val padding: UiInsets = UiInsets.None) {
    fun place(width: Int, height: Int, items: List<UiAnchoredItem>): List<UiLayoutPlacement> {
        require(width >= padding.left + padding.right) { "Anchor layout width is smaller than its padding" }
        require(height >= padding.top + padding.bottom) { "Anchor layout height is smaller than its padding" }
        require(items.map(UiAnchoredItem::key).distinct().size == items.size) { "Anchor layout keys must be unique" }
        val innerWidth = width - padding.left - padding.right
        val innerHeight = height - padding.top - padding.bottom
        return items.map { item ->
            val horizontal = when (item.anchor) {
                UiAnchor.TOP_LEFT, UiAnchor.CENTER_LEFT, UiAnchor.BOTTOM_LEFT -> 0
                UiAnchor.TOP_CENTER, UiAnchor.CENTER, UiAnchor.BOTTOM_CENTER -> (innerWidth - item.width) / 2
                UiAnchor.TOP_RIGHT, UiAnchor.CENTER_RIGHT, UiAnchor.BOTTOM_RIGHT -> innerWidth - item.width
            }
            val vertical = when (item.anchor) {
                UiAnchor.TOP_LEFT, UiAnchor.TOP_CENTER, UiAnchor.TOP_RIGHT -> 0
                UiAnchor.CENTER_LEFT, UiAnchor.CENTER, UiAnchor.CENTER_RIGHT -> (innerHeight - item.height) / 2
                UiAnchor.BOTTOM_LEFT, UiAnchor.BOTTOM_CENTER, UiAnchor.BOTTOM_RIGHT -> innerHeight - item.height
            }
            UiLayoutPlacement(
                item.key,
                UiRect(
                    padding.left + horizontal + item.offsetX,
                    padding.top + vertical + item.offsetY,
                    item.width,
                    item.height
                )
            )
        }
    }
}
