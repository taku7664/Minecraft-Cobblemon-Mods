package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvolutionFamilyIndexTest {
    @Test
    fun `replacement discards stale evolution edges`() {
        val index = EvolutionFamilyIndex<String>()
        index.replace(listOf("a" to "b", "b" to "c"))
        assertTrue(index.connected("a", "c"))

        index.replace(listOf("a" to "d"))
        assertFalse(index.connected("a", "c"))
        assertTrue(index.connected("a", "d"))
    }

    @Test
    fun `disconnected species stay rejected`() {
        val index = EvolutionFamilyIndex<String>()
        index.replace(listOf("a" to "b", "x" to "y"))

        assertFalse(index.connected("a", "x"))
    }
}
