package jbro.cobblemon.policy.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardChampionGradientTest {
    private fun red(color: Int) = color shr 16 and 0xFF
    private fun green(color: Int) = color shr 8 and 0xFF

    @Test
    fun `every letter stays red`() {
        for (index in 0 until 6) for (millis in 0L until HardChampionGradient.PERIOD_MILLIS step 50) {
            val color = HardChampionGradient.color(index, millis)
            assertTrue(red(color) >= 0xB0 && green(color) <= 0x70) { "%06X at $index, $millis".format(color) }
        }
    }

    @Test
    fun `the gradient moves and differs across letters`() {
        assertNotEquals(HardChampionGradient.color(0, 0), HardChampionGradient.color(0, 500))
        assertNotEquals(HardChampionGradient.color(0, 0), HardChampionGradient.color(2, 0))
        assertEquals(HardChampionGradient.color(3, 123), HardChampionGradient.color(3, 123 + HardChampionGradient.PERIOD_MILLIS))
    }
}
