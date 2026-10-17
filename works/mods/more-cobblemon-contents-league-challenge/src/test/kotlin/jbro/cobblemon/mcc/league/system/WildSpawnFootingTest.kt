package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WildSpawnFootingTest {
    private val flat = List(24) { 64 }

    @Test
    fun `level ground and a gentle slope are fine`() {
        assertNull(WildSpawnFooting.rejection(64, flat, 0))
        assertNull(WildSpawnFooting.rejection(66, List(12) { 63 } + List(12) { 67 }, 0))
    }

    @Test
    fun `a spot standing well above the ground around it, like a trunk or pillar top, is refused`() {
        assertEquals("raised", WildSpawnFooting.rejection(70, flat, 0))
        assertNull(WildSpawnFooting.rejection(64 + WildSpawnFooting.MAX_RISE, flat, 0))
    }

    @Test
    fun `a spot with two or more hollow neighbours is refused`() {
        assertNull(WildSpawnFooting.rejection(64, flat, 1))
        assertEquals("hollow", WildSpawnFooting.rejection(64, flat, 2))
    }
}
