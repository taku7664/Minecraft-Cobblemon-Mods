package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.client.MccShopLayout
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hub's layouts moved onto the UI kit's layout tree. These compare every rectangle with the hand-written
 * arithmetic they replaced (kept below, copied from before the move) across a sweep of sizes, so the move changes
 * no pixel. Sizes where the old code could not build a rectangle at all are skipped.
 */
class HubLayoutEquivalenceTest {
    @Test
    fun `the hub frame and its tab rail match the hand-written layout`() {
        var compared = 0
        for (width in 1..1000 step 3) for (height in 1..600 step 2) for (tabs in listOf(0, 6, 7, 9)) {
            val old = legacy { LegacyHub.calculate(width, height, tabs) } ?: continue
            val new = MccHubLayout.calculate(width, height, tabs)
            val at = "$width x $height, $tabs tabs"
            assertEquals(old.shell, new.shell, at)
            assertEquals(old.header, new.header, at)
            assertEquals(old.rail, new.rail, at)
            assertEquals(old.content, new.content, at)
            assertEquals(old.closeButton, new.closeButton, at)
            assertEquals(old.balance, new.balance, at)
            assertEquals(old.brandScale, new.brandScale, at)
            assertEquals(old.tabHeight, new.tabHeight, at)
            assertEquals(old.visibleTabCount, new.visibleTabCount(), at)
            val oldTabs = (0 until old.visibleTabCount).mapNotNull { index -> legacy { old.tabButton(index) } }
            if (oldTabs.size == old.visibleTabCount) assertEquals(oldTabs, new.tabButtons(), at)
            compared++
        }
        assertTrue(compared > 300_000)
    }

    @Test
    fun `the dashboard matches the hand-written layout`() = sweep { bounds ->
        val old = legacy { LegacyDashboard.calculate(bounds) } ?: return@sweep false
        assertEquals(old, MccDashboardLayout.calculate(bounds), bounds.toString())
        true
    }

    @Test
    fun `the shop counter and its cart match the hand-written layout`() = sweep { bounds ->
        val old = legacy { LegacyShop.calculate(bounds) } ?: return@sweep false
        assertEquals(old, MccShopLayout.calculate(bounds), bounds.toString())
        val oldCart = legacy { LegacyShop.cartBody(bounds, 30) }
        if (oldCart != null) {
            val cart = MccShopLayout.cartBody(bounds, 30)
            assertEquals(oldCart, Triple(cart["list"], cart["summary"], cart["button"]), bounds.toString())
        }
        true
    }

    @Test
    fun `columns, card bodies and equal parts match the hand-written arithmetic`() = sweep { bounds ->
        listOf(intArrayOf(58, 42), intArrayOf(55, 45), intArrayOf(3, 3, 4), intArrayOf(1, 1)).forEach { weights ->
            legacy { LegacyKit.columns(bounds, *weights) }?.let { assertEquals(it, MccHubKit.columns(bounds, *weights), "$bounds ${weights.toList()}") }
        }
        legacy { LegacyKit.cardBody(bounds) }?.let { assertEquals(it, MccHubKit.cardBody(bounds), bounds.toString()) }
        (1..5).forEach { count ->
            legacy { LegacyKit.equalParts(bounds, count) }?.let { assertEquals(it, MccHubKit.equalParts(bounds, count), "$bounds $count") }
        }
        true
    }

    @Test
    fun `footers match the hand-written placement`() {
        val naturals = listOf(listOf(), listOf(96), listOf(40, 118), listOf(52, 60, 96))
        var compared = 0
        for (width in 1..760) {
            val footer = UiRect(13, 200, width, MccHubKit.FOOTER_HEIGHT)
            naturals.forEach { startWidths ->
                naturals.forEach { endWidths ->
                    val start = startWidths.map { natural -> { room: Int -> UiSize(minOf(natural, room), 26) } }
                    val end = endWidths.map { natural -> { room: Int -> UiSize(minOf(natural, room), 26) } }
                    val old = legacy { LegacyKit.footer(footer, start, end) } ?: return@forEach
                    assertEquals(old, MccHubKit.footerLayout(footer, start, end), "$footer $startWidths $endWidths")
                    compared++
                }
            }
        }
        assertTrue(compared > 10_000)
    }

    @Test
    fun `choice rows keep their label column and stacking`() = sweep(step = 5) { bounds ->
        listOf(listOf(40), listOf(40, 72, 55), listOf(90, 30)).forEach { labels ->
            MccHubKit.ChoiceMode.entries.forEach { mode ->
                val old = legacy { LegacyKit.choices(bounds, mode, labels) } ?: return@forEach
                val layout = MccHubKit.choiceLayout(bounds, mode, labels)
                assertEquals(old, labels.indices.map { layout.find("label.$it") to layout["controls.$it"] }, "$bounds $mode $labels")
            }
        }
        true
    }

    @Test
    fun `paged lists split pages and place rows and the pager as before`() = sweep(step = 5) { bounds ->
        listOf(1, 4, 9, 30).forEach { count ->
            listOf(26, 34).forEach { rowHeight ->
                (0..3).forEach { page ->
                    val old = legacy { LegacyKit.pagedList(bounds, count, rowHeight, page) } ?: return@forEach
                    val new = MccHubKit.pagedListLayout(bounds, count, rowHeight, page)
                    val pager = if (new.pages > 1) Triple(new.layout["previous"], new.layout["page"], new.layout["next"]) else null
                    assertEquals(old, LegacyKit.Paged(new.perPage, new.pages, new.page, new.layout.list("row"), pager),
                        "$bounds $count $rowHeight $page")
                }
            }
        }
        true
    }

    @Test
    fun `portrait card grids pick and split cells as before`() = sweep(step = 4) { bounds ->
        listOf(1, 2, 3, 4, 6).forEach { count ->
            legacy { LegacyPortraits.grid(bounds, count) }?.let { assertEquals(it, MccHubPortraitCards.grid(bounds, count), "$bounds $count") }
        }
        true
    }

    private fun sweep(step: Int = 3, check: (UiRect) -> Boolean) {
        var compared = 0
        for (width in 1..760 step step) for (height in 1..480 step step) {
            if (check(UiRect(13, 7, width, height))) compared++
        }
        assertTrue(compared > 1_000, "only $compared sizes compared")
    }
}

/** Runs the old arithmetic, or returns null where it could not build its rectangles. */
internal inline fun <T> legacy(block: () -> T): T? = try {
    block()
} catch (_: IllegalArgumentException) {
    null
}

// ---- The hand-written layouts as they were before the layout tree, kept only to compare against. ----

private class LegacyHub(
    val shell: UiRect, val header: UiRect, val rail: UiRect, val content: UiRect, val closeButton: UiRect,
    val balance: UiRect, val brandScale: Float, val tabHeight: Int,
) {
    val visibleTabCount: Int get() = tabsFitting(rail, tabHeight)

    fun tabButton(index: Int): UiRect = UiRect(rail.x + 6, rail.y + 6 + index * (tabHeight + 3), rail.width - 12, tabHeight)

    companion object {
        private fun tabsFitting(rail: UiRect, tabHeight: Int): Int = ((rail.height - 12 + 3) / (tabHeight + 3)).coerceAtLeast(0)

        fun calculate(screenWidth: Int, screenHeight: Int, tabCount: Int): LegacyHub {
            val shellWidth = (screenWidth - 12).coerceIn(1, 720)
            val shellHeight = (screenHeight - 8).coerceIn(1, 400)
            val shell = UiRect((screenWidth - shellWidth) / 2, (screenHeight - shellHeight) / 2, shellWidth, shellHeight)
            val headerHeight = if (shellHeight >= 220) 32 else 26
            val header = UiRect(shell.x + 4, shell.y + 4, shell.width - 8, headerHeight)
            val bodyTop = header.bottom + 5
            val bodyHeight = (shell.bottom - 6 - bodyTop).coerceAtLeast(1)
            val railWidth = (shell.width * 22 / 100).coerceIn(84, 120)
            val rail = UiRect(shell.x + 6, bodyTop, railWidth, bodyHeight)
            val contentLeft = rail.right + 5
            val content = UiRect(contentLeft, bodyTop, (shell.right - 6 - contentLeft).coerceAtLeast(1), bodyHeight)
            val closeButton = UiRect(header.right - 52 - 5, header.y + (header.height - 26) / 2, 52, 26)
            val balance = UiRect(closeButton.x - 90 - 6, header.y + 4, 90, header.height - 8)
            val tabHeight = if (tabsFitting(rail, 26) >= tabCount) 26 else 20
            return LegacyHub(shell, header, rail, content, closeButton, balance, if (headerHeight >= 32) 2f else 1.5f, tabHeight)
        }
    }
}

private object LegacyDashboard {
    fun calculate(bounds: UiRect): MccDashboardLayout {
        val gap = 3
        val trainerWidth = (bounds.width * 34 / 100).coerceIn(96, 150).coerceAtMost((bounds.width - gap - 60).coerceAtLeast(1))
        val trainer = UiRect(bounds.x, bounds.y, trainerWidth, bounds.height)
        val records = UiRect(trainer.right + gap, bounds.y, (bounds.width - trainerWidth - gap).coerceAtLeast(1), bounds.height)
        val trainerBody = LegacyKit.cardBody(trainer)
        val model = UiRect(trainerBody.x, trainerBody.y, trainerBody.width, (trainerBody.height - 13).coerceAtLeast(16))
        val recordsBody = LegacyKit.cardBody(records)
        val summary = UiRect(recordsBody.x, recordsBody.y, recordsBody.width, 22)
        val rows = UiRect(recordsBody.x - 2, summary.bottom + 4, recordsBody.width + 4,
            (recordsBody.bottom + 2 - summary.bottom - 4).coerceAtLeast(1))
        return MccDashboardLayout(trainer, model, model.bottom + 4, records, summary, rows)
    }
}

private object LegacyShop {
    fun calculate(bounds: UiRect): MccShopLayout {
        val gap = 3
        val withModels = bounds.width >= 420
        val modelWidth = if (withModels) (bounds.width * 17 / 100).coerceIn(56, 110) else 0
        val keeper = if (withModels) UiRect(bounds.x, bounds.y, modelWidth, bounds.height) else null
        val viewer = if (withModels) UiRect(bounds.right - modelWidth, bounds.y, modelWidth, bounds.height) else null
        val left = keeper?.let { it.right + gap } ?: bounds.x
        val right = viewer?.let { it.x - gap } ?: bounds.right
        val listWidth = (right - left - 12) / 2
        val catalog = UiRect(left, bounds.y, listWidth, bounds.height)
        val arrow = UiRect(catalog.right, bounds.y, 12, bounds.height)
        val cart = UiRect(arrow.right, bounds.y, right - arrow.right, bounds.height)
        return MccShopLayout(keeper, catalog, arrow, cart, viewer)
    }

    fun cartBody(body: UiRect, summaryHeight: Int): Triple<UiRect, UiRect, UiRect> {
        val button = UiRect(body.x, body.bottom - 26, body.width, 26)
        val summary = UiRect(body.x, button.y - 4 - summaryHeight, body.width, summaryHeight)
        val list = UiRect(body.x, body.y, body.width, (summary.y - 4 - body.y).coerceAtLeast(1))
        return Triple(list, summary, button)
    }
}

internal object LegacyKit {
    fun columns(rect: UiRect, vararg weights: Int): List<UiRect> {
        val total = weights.sum().coerceAtLeast(1)
        val room = rect.width - 3 * (weights.size - 1)
        var x = rect.x
        return weights.mapIndexed { index, weight ->
            val width = if (index == weights.lastIndex) rect.right - x else room * weight / total
            UiRect(x, rect.y, width.coerceAtLeast(1), rect.height).also { x += width + 3 }
        }
    }

    fun cardBody(rect: UiRect): UiRect =
        UiRect(rect.x + 6, rect.y + 2 + 15 + 5, (rect.width - 12).coerceAtLeast(1), (rect.height - 27).coerceAtLeast(1))

    fun equalParts(rect: UiRect, count: Int): List<UiRect> {
        val width = (rect.width - (count - 1) * 2) / count
        return (0 until count).map { index -> UiRect(rect.x + index * (width + 2), rect.y, width, rect.height) }
    }

    fun footer(rect: UiRect, start: List<(Int) -> UiSize>, end: List<(Int) -> UiSize>): Pair<List<UiRect?>, List<UiRect>> {
        val y = rect.y + (rect.height - 26 + 1) / 2 + 1
        var right = rect.right
        val ends = end.asReversed().map { measure ->
            val size = measure((rect.width / 2).coerceAtLeast(40))
            UiRect(right - size.width, y, size.width, size.height).also { right = it.x - 4 }
        }.asReversed()
        var left = rect.x
        val starts = start.map { measure ->
            val room = right - left - 4
            if (room < 24) return@map null
            val size = measure(room)
            UiRect(left, y, size.width, size.height).also { left += size.width + 4 }
        }
        return starts to ends
    }

    fun choices(rect: UiRect, mode: MccHubKit.ChoiceMode, labelWidths: List<Int>): List<Pair<UiRect?, UiRect>> {
        val labelWidth = labelWidths.max()
        var y = rect.y
        return labelWidths.map {
            when (mode) {
                MccHubKit.ChoiceMode.INLINE -> (UiRect(rect.x, y, labelWidth, 26) to UiRect(rect.x + labelWidth, y, rect.width - labelWidth, 26))
                    .also { y += 26 + 3 }
                MccHubKit.ChoiceMode.TITLED -> (UiRect(rect.x, y, rect.width, 10) to UiRect(rect.x, y + 12, rect.width, 26)).also { y += 38 + 3 }
                MccHubKit.ChoiceMode.COMPACT -> (null to UiRect(rect.x, y, rect.width, 26)).also { y += 26 + 3 }
            }
        }
    }

    data class Paged(val perPage: Int, val pages: Int, val page: Int, val rows: List<UiRect>, val pager: Triple<UiRect, UiRect, UiRect>?)

    fun pagedList(rect: UiRect, count: Int, rowHeight: Int, page: Int): Paged {
        val step = rowHeight + 2
        var perPage = ((rect.height + 2) / step).coerceAtLeast(1)
        if (count > perPage) perPage = ((rect.height - 26 - 3 + 2) / step).coerceAtLeast(1)
        val pages = (count + perPage - 1) / perPage
        val current = page.coerceIn(0, pages - 1)
        val rows = (0 until count).drop(current * perPage).take(perPage).indices.map { index ->
            UiRect(rect.x, rect.y + index * step, rect.width, step - 2)
        }
        if (pages <= 1) return Paged(perPage, pages, current, rows, null)
        val y = rect.bottom - 26
        return Paged(perPage, pages, current, rows,
            Triple(UiRect(rect.x, y, 40, 26), UiRect(rect.x + 44, y, rect.width - 88, 26), UiRect(rect.right - 40, y, 40, 26)))
    }
}

private object LegacyPortraits {
    private class Candidate(val columns: Int, val rows: Int, val width: Int, val height: Int, val stacked: Boolean, val side: Int)

    fun grid(body: UiRect, count: Int): List<MccHubPortraitCards.Cell> {
        if (count <= 0) return emptyList()
        val best = (1..count).mapNotNull { columns -> candidate(body, count, columns) }
            .maxWithOrNull(compareBy<Candidate> { it.side }.thenBy { -it.rows }) ?: return emptyList()
        return (0 until count).map { index ->
            val cell = UiRect(body.x + (index % best.columns) * (best.width + 2), body.y + (index / best.columns) * (best.height + 2),
                best.width, best.height)
            val side = best.side
            if (best.stacked) {
                MccHubPortraitCards.Cell(cell, UiRect(cell.x + (cell.width - side) / 2, cell.y + 2, side, side),
                    UiRect(cell.x + 4, cell.bottom - 22, cell.width - 8, 20), true)
            } else {
                MccHubPortraitCards.Cell(cell, UiRect(cell.x + 2, cell.y + (cell.height - side) / 2, side, side),
                    UiRect(cell.x + side + 6, cell.y + (cell.height - 20) / 2, (cell.width - side - 9).coerceAtLeast(1), 20), false)
            }
        }
    }

    private fun candidate(body: UiRect, count: Int, columns: Int): Candidate? {
        val rows = (count + columns - 1) / columns
        val width = (body.width - 2 * (columns - 1)) / columns
        val height = (body.height - 2 * (rows - 1)) / rows
        if (width < 64 || height < 24) return null
        val stacked = height >= 44 && width * 5 <= height * 7
        val side = if (stacked) minOf(height - 22 - 4, width - 4) else minOf(height - 4, width / 2)
        if (side < 8) return null
        return Candidate(columns, rows, width, height, stacked, side)
    }
}
