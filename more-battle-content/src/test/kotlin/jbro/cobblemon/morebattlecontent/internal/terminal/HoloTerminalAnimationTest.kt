package jbro.cobblemon.morebattlecontent.internal.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HoloTerminalAnimationTest {
    @Test
    fun `one render timestamp deterministically drives every animation value`() {
        val first = HoloTerminalAnimation.frame(100, 0.5f)
        val second = HoloTerminalAnimation.frame(100, 0.5f)

        assertEquals(first, second)
        assertTrue(first.rotationRadians in 0.0f..HoloTerminalAnimation.FULL_ROTATION)
        assertTrue(first.pulse in 0.0f..1.0f)
        assertTrue(first.scanHeight in 0.55f..1.35f)
    }

    @Test
    fun `partial tick must be a finite frame fraction`() {
        assertThrows(IllegalArgumentException::class.java) { HoloTerminalAnimation.frame(0, -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { HoloTerminalAnimation.frame(0, 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { HoloTerminalAnimation.frame(0, Float.NaN) }
    }
}
