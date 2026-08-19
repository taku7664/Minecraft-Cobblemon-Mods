package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShopVerticalScrollMetricsTest {
    @Test
    fun `wheel movement and dragged thumb share one clamped vertical offset`() {
        val metrics = MbcVerticalScrollMetrics.calculate(
            viewportHeight = 120,
            contentHeight = 400,
            trackHeight = 120,
        )

        assertEquals(280, metrics.maxOffset)
        assertEquals(36, metrics.thumbHeight)
        assertEquals(28, metrics.afterWheel(0, -1.0))
        assertEquals(0, metrics.afterWheel(0, 1.0))
        assertEquals(280, metrics.offsetForThumbTop(trackTop = 20, thumbTop = 104))
    }

    @Test
    fun `catalog narrower than viewport does not scroll`() {
        val metrics = MbcVerticalScrollMetrics.calculate(120, 80, 120)

        assertEquals(0, metrics.maxOffset)
        assertEquals(120, metrics.thumbHeight)
        assertEquals(0, metrics.afterWheel(0, -5.0))
    }
}
