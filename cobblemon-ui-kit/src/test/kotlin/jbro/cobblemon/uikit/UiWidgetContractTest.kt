package jbro.cobblemon.uikit

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiWidgetContractTest {
    @Test
    fun `progress exposes a clamped fraction without losing the source values`() {
        val progress = UiProgressSpec(value = 7, maximum = 10)
        val overflow = UiProgressSpec(value = 14, maximum = 10)

        assertEquals(0.7f, progress.fraction)
        assertEquals(1f, overflow.fraction)
        assertEquals(14, overflow.value)
        assertThrows(IllegalArgumentException::class.java) { UiProgressSpec(1, 0) }
    }

    @Test
    fun `scroll state clamps offsets and derives a reusable thumb`() {
        val scroll = UiScrollState(viewportHeight = 80, contentHeight = 200, step = 20)

        assertTrue(scroll.scroll(-1.0))
        assertEquals(20, scroll.offset)
        scroll.jumpTo(999)
        assertEquals(120, scroll.offset)
        assertFalse(scroll.scroll(-1.0))
        assertEquals(UiVerticalRange(58, 90), scroll.thumb(trackTop = 10, trackHeight = 80))
    }

    @Test
    fun `scroll state supports keyboard paging and reveals focused ranges`() {
        val scroll = UiScrollState(viewportHeight = 80, contentHeight = 240, step = 20)

        assertTrue(scroll.page(1))
        assertEquals(80, scroll.offset)
        assertTrue(scroll.ensureVisible(UiVerticalRange(170, 190)))
        assertEquals(110, scroll.offset)
        assertTrue(scroll.home())
        assertEquals(0, scroll.offset)
        assertTrue(scroll.end())
        assertEquals(160, scroll.offset)
    }

    @Test
    fun `tab list badge and toggle reject empty labels`() {
        val blank = Component.literal(" ")

        assertThrows(IllegalArgumentException::class.java) { UiTabSpec(blank) }
        assertThrows(IllegalArgumentException::class.java) { UiListItemSpec(blank) }
        assertThrows(IllegalArgumentException::class.java) { UiBadgeSpec(blank) }
        assertThrows(IllegalArgumentException::class.java) { UiToggleSpec(blank, value = false) }
    }
}
