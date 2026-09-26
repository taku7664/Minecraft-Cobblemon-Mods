package jbro.cobblemon.uikit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UiLayoutContractTest {
    @Test
    fun `horizontal stack applies padding gap and cross-axis centering`() {
        val layout = UiStackLayout(
            axis = UiAxis.HORIZONTAL,
            gap = 2,
            padding = UiInsets.all(4),
            crossAlignment = UiCrossAlignment.CENTER
        )

        val placements = layout.place(
            100,
            40,
            listOf(UiLayoutItem("a", 20, 10), UiLayoutItem("b", 20, 20))
        )

        assertEquals(UiRect(4, 15, 20, 10), placements[0].bounds)
        assertEquals(UiRect(26, 10, 20, 20), placements[1].bounds)
    }

    @Test
    fun `flow wraps items without screen-specific coordinates`() {
        val layout = UiFlowLayout(horizontalGap = 3, verticalGap = 4, padding = UiInsets.all(2))

        val placements = layout.place(
            50,
            listOf(
                UiLayoutItem("a", 20, 10),
                UiLayoutItem("b", 20, 10),
                UiLayoutItem("c", 20, 10)
            )
        )

        assertEquals(UiRect(2, 2, 20, 10), placements[0].bounds)
        assertEquals(UiRect(25, 2, 20, 10), placements[1].bounds)
        assertEquals(UiRect(2, 16, 20, 10), placements[2].bounds)
    }

    @Test
    fun `weighted stack gives remainder pixels to the final weighted item`() {
        val placements = UiStackLayout(UiAxis.HORIZONTAL, gap = 1).place(
            101,
            20,
            listOf(
                UiLayoutItem("a", 10, 10, weight = 1),
                UiLayoutItem("b", 10, 10, weight = 1),
                UiLayoutItem("fixed", 10, 10)
            )
        )

        assertEquals(44, placements[0].bounds.width)
        assertEquals(45, placements[1].bounds.width)
        assertEquals(101, placements.last().bounds.right)
    }

    @Test
    fun `grid assigns stable equal-width columns`() {
        val layout = UiGridLayout(columns = 3, horizontalGap = 2, verticalGap = 4, padding = UiInsets.all(4))
        val placements = layout.place(
            100,
            listOf(
                UiLayoutItem("a", 1, 10),
                UiLayoutItem("b", 1, 12),
                UiLayoutItem("c", 1, 8),
                UiLayoutItem("d", 1, 6)
            )
        )

        assertEquals(UiRect(4, 4, 29, 10), placements[0].bounds)
        assertEquals(UiRect(35, 4, 29, 12), placements[1].bounds)
        assertEquals(UiRect(66, 4, 29, 8), placements[2].bounds)
        assertEquals(UiRect(4, 20, 29, 6), placements[3].bounds)
    }

    @Test
    fun `invalid layout values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UiInsets(-1, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { UiLayoutItem("", 10, 10) }
        assertThrows(IllegalArgumentException::class.java) { UiGridLayout(0) }
    }
}
