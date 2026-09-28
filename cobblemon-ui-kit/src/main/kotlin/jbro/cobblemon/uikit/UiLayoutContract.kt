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

enum class UiAxis { HORIZONTAL, VERTICAL }

/** Where something sits along an axis with room to spare; [STRETCH] fills the room instead. */
enum class UiCrossAlignment { START, CENTER, END, STRETCH }

/** How a child claims room along its container's main axis, or how a grid track is sized. */
sealed interface UiLength {
    /** Exactly [size] pixels. */
    data class Fixed(val size: Int) : UiLength {
        init {
            require(size >= 0) { "Fixed length must not be negative" }
        }
    }

    /** The child's own measured size, or for a grid track the largest cell measured in it. */
    data object Content : UiLength

    /** [percent] of the container's whole size, padding included, clamped to [min]..[max]. */
    data class Percent(val percent: Int, val min: Int = 0, val max: Int = Int.MAX_VALUE) : UiLength {
        init {
            require(percent >= 0) { "Percent length must not be negative" }
            require(min in 0..max) { "Percent length bounds are invalid" }
        }
    }

    /**
     * A [weight] share of the room the sized children leave. A squeezed share is still drawn [min] wide but does
     * not push its neighbours. A [reserve] makes the sized children give way until the shares keep that much.
     */
    data class Weight(val weight: Int = 1, val min: Int = 0, val reserve: Int = 0) : UiLength {
        init {
            require(weight > 0) { "Weight must be positive" }
            require(min >= 0 && reserve >= 0) { "Weight bounds must not be negative" }
        }
    }
}

/**
 * How a child sits across a row or column: stretched between [before] and [after], or [size] pixels placed by
 * [align] inside that room.
 */
data class UiCross(
    val size: Int? = null,
    val align: UiCrossAlignment = UiCrossAlignment.START,
    val before: Int = 0,
    val after: Int = 0,
) {
    init {
        require(size == null || size >= 0) { "Cross size must not be negative" }
        require(before >= 0 && after >= 0) { "Cross margins must not be negative" }
    }

    companion object {
        val Stretch = UiCross()

        fun centered(size: Int): UiCross = UiCross(size, UiCrossAlignment.CENTER)
    }
}

/** Where a row or column packs its children when they leave room over. */
enum class UiJustify { START, CENTER, END }

/** Who gets the pixels that dividing the room by weight leaves over. */
enum class UiRemainder {
    /** The last weighted child, so the shares end exactly at the container's edge. */
    LAST,

    /** Nobody: every share is rounded down alike and the rest stays empty after them. */
    NONE,
}

/** The order a grid fills its cells in. */
enum class UiGridOrder { ROW_MAJOR, COLUMN_MAJOR }

/** One child of a row or column. */
data class UiSlot(val node: UiLayoutNode, val length: UiLength, val cross: UiCross = UiCross.Stretch)

/**
 * A responsive layout: a tree of containers solved against whatever rectangle it is given. Every leaf with a key
 * ends up with a rectangle in the [UiLayoutResult]; containers measure their children first for [UiLength.Content]
 * and [Responsive] nodes rebuild themselves from the size they get. Everything is plain integer arithmetic, so a
 * layout is testable without Minecraft and lands on whole GUI pixels.
 */
sealed class UiLayoutNode {
    /** The size this node would like, used where a parent sizes it by [UiLength.Content]. */
    abstract fun measure(): UiSize

    internal abstract fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>)

    fun solve(bounds: UiRect): UiLayoutResult {
        val rects = LinkedHashMap<String, UiRect>()
        arrange(bounds, rects)
        return UiLayoutResult(rects)
    }

    /** A rectangle the result names [key]; a leaf without a key only takes room. */
    class Leaf(val key: String?, private val width: Int = 0, private val height: Int = 0) : UiLayoutNode() {
        init {
            require(key == null || key.isNotBlank()) { "Layout keys must not be blank" }
            require(width >= 0 && height >= 0) { "Leaf size must not be negative" }
        }

        override fun measure() = UiSize(width, height)

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            val key = key ?: return
            require(key !in into) { "Layout key $key is used twice" }
            into[key] = bounds
        }
    }

    /** Children side by side along [axis], [gap] apart inside [padding]. */
    class Linear(
        val axis: UiAxis,
        val slots: List<UiSlot>,
        val gap: Int = 0,
        val padding: UiInsets = UiInsets.None,
        val justify: UiJustify = UiJustify.START,
        val remainder: UiRemainder = UiRemainder.LAST,
    ) : UiLayoutNode() {
        init {
            require(gap >= 0) { "Gap must not be negative" }
        }

        private val horizontal get() = axis == UiAxis.HORIZONTAL

        override fun measure(): UiSize {
            val main = slots.sumOf { slot -> lengthFloor(slot.length) { slot.node.measure().along(axis) } } +
                gap * (slots.size - 1).coerceAtLeast(0)
            val cross = slots.maxOfOrNull { slot ->
                (slot.cross.size ?: slot.node.measure().across(axis)) + slot.cross.before + slot.cross.after
            } ?: 0
            return if (horizontal) {
                UiSize(main + padding.left + padding.right, cross + padding.top + padding.bottom)
            } else {
                UiSize(cross + padding.left + padding.right, main + padding.top + padding.bottom)
            }
        }

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            val outer = if (horizontal) bounds.width else bounds.height
            val mainStart = if (horizontal) bounds.x + padding.left else bounds.y + padding.top
            val inner = outer - if (horizontal) padding.left + padding.right else padding.top + padding.bottom
            val crossStart = if (horizontal) bounds.y + padding.top else bounds.x + padding.left
            val crossInner = if (horizontal) bounds.height - padding.top - padding.bottom else bounds.width - padding.left - padding.right
            val tracks = solveTracks(slots.map(UiSlot::length), { slots[it].node.measure().along(axis) }, outer, inner, gap, remainder)
            val used = tracks.raw.sum() + gap * (slots.size - 1).coerceAtLeast(0)
            var cursor = mainStart + when (justify) {
                UiJustify.START -> 0
                UiJustify.CENTER -> (inner - used) / 2
                UiJustify.END -> inner - used
            }
            slots.forEachIndexed { index, slot ->
                val (crossAt, crossSize) = place(slot.cross, crossStart, crossInner)
                val size = tracks.shown[index]
                val rect = if (horizontal) UiRect(cursor, crossAt, size, crossSize) else UiRect(crossAt, cursor, crossSize, size)
                slot.node.arrange(rect, into)
                cursor += tracks.raw[index] + gap
            }
        }

        private fun place(cross: UiCross, start: Int, room: Int): Pair<Int, Int> {
            val regionStart = start + cross.before
            val region = room - cross.before - cross.after
            val size = cross.size
            if (size == null || cross.align == UiCrossAlignment.STRETCH) return regionStart to region.coerceAtLeast(0)
            return when (cross.align) {
                UiCrossAlignment.CENTER -> regionStart + (region - size) / 2
                UiCrossAlignment.END -> regionStart + region - size
                else -> regionStart
            } to size
        }
    }

    /** [child] moved in by each edge; a negative edge reaches out past the bounds. Sizes stay at least [min]. */
    class Inset(
        val child: UiLayoutNode,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val min: Int = 0,
    ) : UiLayoutNode() {
        init {
            require(min >= 0) { "Inset minimum must not be negative" }
        }

        override fun measure(): UiSize = child.measure().let {
            UiSize((it.width + left + right).coerceAtLeast(0), (it.height + top + bottom).coerceAtLeast(0))
        }

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            child.arrange(UiRect(bounds.x + left, bounds.y + top,
                (bounds.width - left - right).coerceAtLeast(min), (bounds.height - top - bottom).coerceAtLeast(min)), into)
        }
    }

    /**
     * [child] at [width] x [height] (null fills that axis) placed by [horizontal] and [vertical]. [fit] shrinks it
     * to the bounds; [pinStart] keeps an oversized child from starting before the bounds do.
     */
    class Align(
        val child: UiLayoutNode,
        val width: Int? = null,
        val height: Int? = null,
        val horizontal: UiCrossAlignment = UiCrossAlignment.CENTER,
        val vertical: UiCrossAlignment = UiCrossAlignment.CENTER,
        val fit: Boolean = false,
        val pinStart: Boolean = false,
    ) : UiLayoutNode() {
        init {
            require((width ?: 0) >= 0 && (height ?: 0) >= 0) { "Aligned size must not be negative" }
        }

        override fun measure(): UiSize = child.measure().let { UiSize(width ?: it.width, height ?: it.height) }

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            val (x, w) = axis(bounds.x, bounds.width, width, horizontal)
            val (y, h) = axis(bounds.y, bounds.height, height, vertical)
            child.arrange(UiRect(x, y, w, h), into)
        }

        private fun axis(start: Int, room: Int, wanted: Int?, align: UiCrossAlignment): Pair<Int, Int> {
            if (wanted == null || align == UiCrossAlignment.STRETCH) return start to room
            val size = if (fit) wanted.coerceAtMost(room) else wanted
            val offset = when (align) {
                UiCrossAlignment.CENTER -> (room - size) / 2
                UiCrossAlignment.END -> room - size
                else -> 0
            }
            return start + (if (pinStart) offset.coerceAtLeast(0) else offset) to size
        }
    }

    /**
     * [cells] in a grid of [columns] by [rows] tracks. A [UiLength.Content] column is as wide as its widest cell,
     * which lines up the controls after a label column however long each label is.
     */
    class Grid(
        val columns: List<UiLength>,
        val rows: List<UiLength>,
        val cells: List<UiLayoutNode>,
        val columnGap: Int = 0,
        val rowGap: Int = 0,
        val order: UiGridOrder = UiGridOrder.ROW_MAJOR,
        val remainder: UiRemainder = UiRemainder.LAST,
    ) : UiLayoutNode() {
        init {
            require(columns.isNotEmpty() && rows.isNotEmpty()) { "A grid needs tracks" }
            require(cells.size <= columns.size * rows.size) { "A grid has more cells than tracks hold" }
            require(columnGap >= 0 && rowGap >= 0) { "Grid gaps must not be negative" }
        }

        private fun cellAt(index: Int): Pair<Int, Int> = when (order) {
            UiGridOrder.ROW_MAJOR -> index / columns.size to index % columns.size
            UiGridOrder.COLUMN_MAJOR -> index % rows.size to index / rows.size
        }

        private fun measured(axis: UiAxis, track: Int): Int = cells.indices
            .filter { cellAt(it).let { (row, column) -> if (axis == UiAxis.HORIZONTAL) column == track else row == track } }
            .maxOfOrNull { cells[it].measure().along(axis) } ?: 0

        override fun measure(): UiSize = UiSize(
            columns.indices.sumOf { lengthFloor(columns[it]) { measured(UiAxis.HORIZONTAL, it) } } + columnGap * (columns.size - 1),
            rows.indices.sumOf { lengthFloor(rows[it]) { measured(UiAxis.VERTICAL, it) } } + rowGap * (rows.size - 1),
        )

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            val widths = solveTracks(columns, { measured(UiAxis.HORIZONTAL, it) }, bounds.width, bounds.width, columnGap, remainder)
            val heights = solveTracks(rows, { measured(UiAxis.VERTICAL, it) }, bounds.height, bounds.height, rowGap, remainder)
            val xs = widths.starts(bounds.x, columnGap)
            val ys = heights.starts(bounds.y, rowGap)
            cells.forEachIndexed { index, cell ->
                val (row, column) = cellAt(index)
                cell.arrange(UiRect(xs[column], ys[row], widths.shown[column], heights.shown[row]), into)
            }
        }
    }

    /** [children] at their measured sizes, left to right, wrapping to a new line when the next one would not fit. */
    class Flow(
        val children: List<UiLayoutNode>,
        val horizontalGap: Int = 0,
        val verticalGap: Int = 0,
    ) : UiLayoutNode() {
        init {
            require(horizontalGap >= 0 && verticalGap >= 0) { "Flow gaps must not be negative" }
        }

        override fun measure(): UiSize {
            val sizes = children.map(UiLayoutNode::measure)
            return UiSize(sizes.sumOf(UiSize::width) + horizontalGap * (sizes.size - 1).coerceAtLeast(0), sizes.maxOfOrNull(UiSize::height) ?: 0)
        }

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) {
            var x = bounds.x
            var y = bounds.y
            var lineHeight = 0
            children.forEach { child ->
                val size = child.measure()
                if (x != bounds.x && x + size.width > bounds.right) {
                    x = bounds.x
                    y += lineHeight + verticalGap
                    lineHeight = 0
                }
                child.arrange(UiRect(x, y, size.width, size.height), into)
                x += size.width + horizontalGap
                lineHeight = max(lineHeight, size.height)
            }
        }
    }

    /** [children] stacked over the same bounds, such as a frame and what sits inside it. */
    class Layers(val children: List<UiLayoutNode>) : UiLayoutNode() {
        override fun measure(): UiSize = children.map(UiLayoutNode::measure).let { sizes ->
            UiSize(sizes.maxOfOrNull(UiSize::width) ?: 0, sizes.maxOfOrNull(UiSize::height) ?: 0)
        }

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) = children.forEach { it.arrange(bounds, into) }
    }

    /** A node built from the size it is given, for layouts that change shape as the room grows or shrinks. */
    class Responsive(private val preferred: UiSize = UiSize(0, 0), val build: (UiSize) -> UiLayoutNode) : UiLayoutNode() {
        override fun measure(): UiSize = preferred

        override fun arrange(bounds: UiRect, into: MutableMap<String, UiRect>) =
            build(UiSize(bounds.width, bounds.height)).arrange(bounds, into)
    }
}

/** The rectangles a solved layout gave its keyed leaves. */
class UiLayoutResult internal constructor(private val rects: Map<String, UiRect>) {
    val keys: Set<String> get() = rects.keys

    operator fun get(key: String): UiRect = requireNotNull(rects[key]) { "Layout has no rectangle named $key" }

    fun find(key: String): UiRect? = rects[key]

    /** The rectangles named `prefix.0`, `prefix.1`, ... in order, as [UiLayout.keys] makes them. */
    fun list(prefix: String): List<UiRect> = generateSequence(0) { it + 1 }.map { rects["$prefix.$it"] }.takeWhile { it != null }
        .map { it!! }.toList()
}

/** Builds the children of a row or column. */
class UiLinearBuilder internal constructor() {
    internal val slots = mutableListOf<UiSlot>()

    fun add(node: UiLayoutNode, length: UiLength, cross: UiCross = UiCross.Stretch) {
        slots += UiSlot(node, length, cross)
    }

    fun fixed(size: Int, node: UiLayoutNode, cross: UiCross = UiCross.Stretch) = add(node, UiLength.Fixed(size), cross)
    fun fixed(size: Int, key: String, cross: UiCross = UiCross.Stretch) = fixed(size, UiLayout.leaf(key), cross)

    fun weight(node: UiLayoutNode, weight: Int = 1, min: Int = 0, reserve: Int = 0, cross: UiCross = UiCross.Stretch) =
        add(node, UiLength.Weight(weight, min, reserve), cross)

    fun weight(key: String, weight: Int = 1, min: Int = 0, reserve: Int = 0, cross: UiCross = UiCross.Stretch) =
        weight(UiLayout.leaf(key), weight, min, reserve, cross)

    fun percent(percent: Int, node: UiLayoutNode, min: Int = 0, max: Int = Int.MAX_VALUE, cross: UiCross = UiCross.Stretch) =
        add(node, UiLength.Percent(percent, min, max), cross)

    fun percent(percent: Int, key: String, min: Int = 0, max: Int = Int.MAX_VALUE, cross: UiCross = UiCross.Stretch) =
        percent(percent, UiLayout.leaf(key), min, max, cross)

    fun content(node: UiLayoutNode, cross: UiCross = UiCross.Stretch) = add(node, UiLength.Content, cross)

    /** Empty room of [size] pixels; a container's own gap still applies around it. */
    fun space(size: Int) = add(UiLayout.space(), UiLength.Fixed(size))

    /** Empty room that takes a [weight] share, pushing what follows towards the far edge. */
    fun spring(weight: Int = 1) = add(UiLayout.space(), UiLength.Weight(weight))
}

/** Entry points for building [UiLayoutNode] trees. */
object UiLayout {
    fun leaf(key: String, width: Int = 0, height: Int = 0): UiLayoutNode = UiLayoutNode.Leaf(key, width, height)

    fun space(width: Int = 0, height: Int = 0): UiLayoutNode = UiLayoutNode.Leaf(null, width, height)

    fun row(
        gap: Int = 0,
        padding: UiInsets = UiInsets.None,
        justify: UiJustify = UiJustify.START,
        remainder: UiRemainder = UiRemainder.LAST,
        build: UiLinearBuilder.() -> Unit,
    ): UiLayoutNode = UiLayoutNode.Linear(UiAxis.HORIZONTAL, UiLinearBuilder().apply(build).slots, gap, padding, justify, remainder)

    fun column(
        gap: Int = 0,
        padding: UiInsets = UiInsets.None,
        justify: UiJustify = UiJustify.START,
        remainder: UiRemainder = UiRemainder.LAST,
        build: UiLinearBuilder.() -> Unit,
    ): UiLayoutNode = UiLayoutNode.Linear(UiAxis.VERTICAL, UiLinearBuilder().apply(build).slots, gap, padding, justify, remainder)

    fun inset(child: UiLayoutNode, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, min: Int = 0): UiLayoutNode =
        UiLayoutNode.Inset(child, left, top, right, bottom, min)

    fun align(
        child: UiLayoutNode,
        width: Int? = null,
        height: Int? = null,
        horizontal: UiCrossAlignment = UiCrossAlignment.CENTER,
        vertical: UiCrossAlignment = UiCrossAlignment.CENTER,
        fit: Boolean = false,
        pinStart: Boolean = false,
    ): UiLayoutNode = UiLayoutNode.Align(child, width, height, horizontal, vertical, fit, pinStart)

    fun grid(
        columns: List<UiLength>,
        rows: List<UiLength>,
        cells: List<UiLayoutNode>,
        columnGap: Int = 0,
        rowGap: Int = 0,
        order: UiGridOrder = UiGridOrder.ROW_MAJOR,
        remainder: UiRemainder = UiRemainder.LAST,
    ): UiLayoutNode = UiLayoutNode.Grid(columns, rows, cells, columnGap, rowGap, order, remainder)

    fun flow(children: List<UiLayoutNode>, horizontalGap: Int = 0, verticalGap: Int = 0): UiLayoutNode =
        UiLayoutNode.Flow(children, horizontalGap, verticalGap)

    fun layers(vararg children: UiLayoutNode): UiLayoutNode = UiLayoutNode.Layers(children.toList())

    fun responsive(preferred: UiSize = UiSize(0, 0), build: (UiSize) -> UiLayoutNode): UiLayoutNode =
        UiLayoutNode.Responsive(preferred, build)

    /** `prefix.0` .. `prefix.(count - 1)`, the keys [UiLayoutResult.list] reads back. */
    fun keys(prefix: String, count: Int): List<String> = (0 until count).map { "$prefix.$it" }

    /** How many items [size] tall fit in [space] with [gap] between neighbours. */
    fun fittingCount(space: Int, size: Int, gap: Int = 0): Int {
        require(size + gap > 0) { "Items must take room" }
        return ((space + gap) / (size + gap)).coerceAtLeast(0)
    }

    /** [count] positions spread evenly from [first] to [last]; a single one sits halfway. */
    fun spread(first: Int, last: Int, count: Int): List<Int> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf((first + last) / 2)
        else -> (0 until count).map { index -> first + (last - first) * index / (count - 1) }
    }
}

private fun UiSize.along(axis: UiAxis): Int = if (axis == UiAxis.HORIZONTAL) width else height
private fun UiSize.across(axis: UiAxis): Int = if (axis == UiAxis.HORIZONTAL) height else width

/** The smallest a length can be, for measuring a container. */
private inline fun lengthFloor(length: UiLength, measured: () -> Int): Int = when (length) {
    is UiLength.Fixed -> length.size
    UiLength.Content -> measured()
    is UiLength.Percent -> length.min
    is UiLength.Weight -> length.min
}

/** Track sizes along one axis: [raw] moves the cursor, [shown] is what each track is drawn at. */
private class Tracks(val raw: IntArray, val shown: IntArray) {
    fun starts(origin: Int, gap: Int): IntArray {
        var cursor = origin
        return IntArray(raw.size) { index -> cursor.also { cursor += raw[index] + gap } }
    }
}

private fun solveTracks(
    lengths: List<UiLength>,
    measured: (Int) -> Int,
    outer: Int,
    inner: Int,
    gap: Int,
    remainder: UiRemainder,
): Tracks {
    val raw = IntArray(lengths.size)
    val gaps = gap * (lengths.size - 1).coerceAtLeast(0)
    val reserve = lengths.sumOf { (it as? UiLength.Weight)?.reserve ?: 0 }
    var sized = 0
    lengths.forEachIndexed { index, length ->
        val size = when (length) {
            is UiLength.Fixed -> length.size
            UiLength.Content -> measured(index)
            is UiLength.Percent -> (outer * length.percent / 100).coerceIn(length.min, length.max)
            is UiLength.Weight -> return@forEachIndexed
        }
        raw[index] = if (reserve > 0) size.coerceAtMost((inner - gaps - reserve - sized).coerceAtLeast(1)) else size
        sized += raw[index]
    }
    val room = inner - gaps - sized
    val totalWeight = lengths.sumOf { (it as? UiLength.Weight)?.weight ?: 0 }
    val lastWeighted = lengths.indexOfLast { it is UiLength.Weight }
    var given = 0
    lengths.forEachIndexed { index, length ->
        if (length !is UiLength.Weight) return@forEachIndexed
        raw[index] = if (remainder == UiRemainder.LAST && index == lastWeighted) room - given else room * length.weight / totalWeight
        given += raw[index]
    }
    val shown = IntArray(lengths.size) { index ->
        val length = lengths[index]
        (if (length is UiLength.Weight) max(raw[index], length.min) else raw[index]).coerceAtLeast(0)
    }
    return Tracks(raw, shown)
}
