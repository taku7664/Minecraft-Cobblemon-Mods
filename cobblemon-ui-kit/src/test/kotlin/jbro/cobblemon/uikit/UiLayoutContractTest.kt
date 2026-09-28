package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UiLayoutContractTest {
    @Test
    fun `a column docks fixed children to both ends and gives the rest to the weighted one`() {
        val layout = UiLayout.column(gap = 3) {
            fixed(20, "strip")
            weight("body", min = 1)
            fixed(30, "footer")
        }.solve(UiRect(10, 5, 300, 200))

        assertEquals(UiRect(10, 5, 300, 20), layout["strip"])
        assertEquals(UiRect(10, 28, 300, 144), layout["body"])
        assertEquals(UiRect(10, 175, 300, 30), layout["footer"])
    }

    @Test
    fun `a squeezed share keeps its minimum without moving what follows`() {
        val layout = UiLayout.column(gap = 3) {
            fixed(20, "strip")
            weight("body", min = 1)
            fixed(30, "footer")
        }.solve(UiRect(0, 0, 100, 40))

        assertEquals(UiRect(0, 23, 100, 1), layout["body"])
        assertEquals(UiRect(0, 10, 100, 30), layout["footer"])
    }

    @Test
    fun `weights share the room and the last one takes what division leaves`() {
        val layout = UiLayout.row(gap = 3) {
            weight("a", 58)
            weight("b", 42)
        }.solve(UiRect(0, 0, 101, 10))

        assertEquals(UiRect(0, 0, 56, 10), layout["a"])
        assertEquals(UiRect(59, 0, 42, 10), layout["b"])
    }

    @Test
    fun `equal shares without a remainder taker leave the spare pixels at the end`() {
        val layout = UiLayout.row(gap = 2, remainder = UiRemainder.NONE) {
            UiLayout.keys("option", 3).forEach { weight(it) }
        }.solve(UiRect(0, 0, 101, 10))

        assertEquals(listOf(UiRect(0, 0, 32, 10), UiRect(34, 0, 32, 10), UiRect(68, 0, 32, 10)), layout.list("option"))
    }

    @Test
    fun `a percent child is measured against the whole container and clamped`() {
        val node = UiLayout.row(gap = 5, padding = UiInsets(6, 0, 6, 0)) {
            percent(22, "rail", min = 84, max = 120)
            weight("content", min = 1)
        }

        assertEquals(UiRect(6, 0, 84, 50), node.solve(UiRect(0, 0, 300, 50))["rail"])
        assertEquals(UiRect(6, 0, 110, 50), node.solve(UiRect(0, 0, 500, 50))["rail"])
        assertEquals(UiRect(121, 0, 373, 50), node.solve(UiRect(0, 0, 500, 50))["content"])
        assertEquals(UiRect(6, 0, 120, 50), node.solve(UiRect(0, 0, 700, 50))["rail"])
    }

    @Test
    fun `a reserve makes sized siblings give way`() {
        val node = UiLayout.row(gap = 3) {
            percent(34, "trainer", min = 96, max = 150)
            weight("records", min = 1, reserve = 60)
        }

        assertEquals(87, node.solve(UiRect(0, 0, 150, 10))["trainer"].width)
        assertEquals(96, node.solve(UiRect(0, 0, 200, 10))["trainer"].width)
        assertEquals(1, node.solve(UiRect(0, 0, 40, 10))["trainer"].width)
    }

    @Test
    fun `end justification packs children against the far edge`() {
        val layout = UiLayout.row(gap = 2, padding = UiInsets(0, 0, 4, 0), justify = UiJustify.END) {
            fixed(18, "a", UiCross.centered(18))
            fixed(18, "b", UiCross.centered(18))
        }.solve(UiRect(0, 0, 100, 24))

        assertEquals(UiRect(58, 3, 18, 18), layout["a"])
        assertEquals(UiRect(78, 3, 18, 18), layout["b"])
    }

    @Test
    fun `cross margins and alignment place a child across the row`() {
        val layout = UiLayout.row(padding = UiInsets(0, 0, 5, 0)) {
            spring()
            fixed(90, "balance", UiCross(before = 4, after = 4))
            space(6)
            fixed(52, "close", UiCross.centered(26))
        }.solve(UiRect(4, 4, 400, 32))

        assertEquals(UiRect(251, 8, 90, 24), layout["balance"])
        assertEquals(UiRect(347, 7, 52, 26), layout["close"])
    }

    @Test
    fun `insets may reach outside and keep a minimum size`() {
        val layout = UiLayout.inset(UiLayout.leaf("body"), 6, 22, 6, 5, min = 1).solve(UiRect(0, 0, 10, 20))
        assertEquals(UiRect(6, 22, 1, 1), layout["body"])

        val outset = UiLayout.inset(UiLayout.leaf("rows"), left = -2, right = -2, bottom = -2).solve(UiRect(10, 10, 20, 20))
        assertEquals(UiRect(8, 10, 24, 22), outset["rows"])
    }

    @Test
    fun `align centres a bounded child and can shrink it to fit or pin it to the start`() {
        val bounds = UiRect(0, 0, 100, 60)
        assertEquals(UiRect(20, 15, 60, 30), UiLayout.align(UiLayout.leaf("box"), 60, 30).solve(bounds)["box"])
        assertEquals(UiRect(0, 0, 100, 60), UiLayout.align(UiLayout.leaf("box"), height = 80, fit = true).solve(bounds)["box"])
        assertEquals(UiRect(-10, 0, 120, 60), UiLayout.align(UiLayout.leaf("box"), width = 120).solve(bounds)["box"])
        assertEquals(UiRect(0, 0, 120, 60), UiLayout.align(UiLayout.leaf("box"), width = 120,
            horizontal = UiCrossAlignment.END, pinStart = true).solve(bounds)["box"])
    }

    @Test
    fun `a content grid column lines up every row after its longest label`() {
        val labels = listOf(40, 72, 55)
        val cells = labels.flatMapIndexed { row, width -> listOf(UiLayout.leaf("label.$row", width, 26), UiLayout.leaf("controls.$row")) }
        val layout = UiLayout.grid(listOf(UiLength.Content, UiLength.Weight()), List(3) { UiLength.Fixed(26) }, cells, rowGap = 3)
            .solve(UiRect(0, 0, 300, 100))

        assertEquals(listOf(72, 72, 72), layout.list("label").map(UiRect::width))
        assertEquals(List(3) { 72 }, layout.list("controls").map(UiRect::x))
        assertEquals(listOf(0, 29, 58), layout.list("controls").map(UiRect::y))
        assertEquals(228, layout["controls.0"].width)
    }

    @Test
    fun `a column major grid fills columns top to bottom`() {
        val keys = UiLayout.keys("cell", 5)
        val layout = UiLayout.grid(List(2) { UiLength.Fixed(40) }, List(3) { UiLength.Fixed(12) }, keys.map(UiLayout::leaf),
            columnGap = 6, order = UiGridOrder.COLUMN_MAJOR).solve(UiRect(0, 0, 200, 50))

        assertEquals(listOf(UiRect(0, 0, 40, 12), UiRect(0, 12, 40, 12), UiRect(0, 24, 40, 12), UiRect(46, 0, 40, 12), UiRect(46, 12, 40, 12)),
            layout.list("cell"))
    }

    @Test
    fun `a flow wraps at the right edge and keeps measured sizes`() {
        val keys = UiLayout.keys("item", 3)
        val layout = UiLayout.flow(keys.map { UiLayout.leaf(it, 20, 10) }, 3, 4).solve(UiRect(2, 2, 45, 0))

        assertEquals(listOf(UiRect(2, 2, 20, 10), UiRect(25, 2, 20, 10), UiRect(2, 16, 20, 10)), layout.list("item"))
    }

    @Test
    fun `a responsive node rebuilds from the size it gets`() {
        val node = UiLayout.responsive { size ->
            if (size.width >= 420) UiLayout.row { fixed(60, "keeper"); weight("catalog") } else UiLayout.leaf("catalog")
        }

        assertEquals(UiRect(60, 0, 440, 10), node.solve(UiRect(0, 0, 500, 10))["catalog"])
        assertEquals(null, node.solve(UiRect(0, 0, 300, 10)).find("keeper"))
        assertEquals(UiRect(0, 0, 300, 10), node.solve(UiRect(0, 0, 300, 10))["catalog"])
    }

    @Test
    fun `content lengths measure nested containers`() {
        val badge = UiLayout.row(gap = 3) { fixed(16, "icon"); fixed(40, "label") }
        val layout = UiLayout.row(justify = UiJustify.END) { content(badge) }.solve(UiRect(0, 0, 100, 16))

        assertEquals(UiRect(41, 0, 16, 16), layout["icon"])
        assertEquals(UiRect(60, 0, 40, 16), layout["label"])
    }

    @Test
    fun `helpers count fitting items and spread positions`() {
        assertEquals(6, UiLayout.fittingCount(171, 26, 3))
        assertEquals(0, UiLayout.fittingCount(-20, 26, 3))
        assertEquals(listOf(18, 150, 282), UiLayout.spread(18, 282, 3))
        assertEquals(listOf(150), UiLayout.spread(18, 282, 1))
    }

    @Test
    fun `invalid layouts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UiLayout.leaf("") }
        assertThrows(IllegalArgumentException::class.java) { UiLength.Weight(0) }
        assertThrows(IllegalArgumentException::class.java) { UiLength.Percent(10, min = 20, max = 10) }
        assertThrows(IllegalArgumentException::class.java) {
            UiLayout.row { fixed(10, "same"); fixed(10, "same") }.solve(UiRect(0, 0, 40, 10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiLayout.grid(listOf(UiLength.Fixed(1)), listOf(UiLength.Fixed(1)), listOf(UiLayout.leaf("a"), UiLayout.leaf("b")))
        }
    }
}
