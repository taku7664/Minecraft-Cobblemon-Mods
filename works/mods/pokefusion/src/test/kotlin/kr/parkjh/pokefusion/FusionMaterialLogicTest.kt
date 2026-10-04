package kr.parkjh.pokefusion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FusionMaterialLogicTest {
    @Test
    fun `shift click always fills base before materials`() {
        assertEquals(FusionMaterialLogic.InputDestination.BASE, FusionMaterialLogic.nextDestination(true, 0))
        assertEquals(FusionMaterialLogic.InputDestination.MATERIAL, FusionMaterialLogic.nextDestination(false, 0))
        assertEquals(FusionMaterialLogic.InputDestination.MATERIAL, FusionMaterialLogic.nextDestination(false, 8))
        assertEquals(FusionMaterialLogic.InputDestination.NONE, FusionMaterialLogic.nextDestination(false, 9))
    }

    @Test
    fun `material slots stay contiguous and wrap after five inputs`() {
        assertEquals(listOf(31), FusionMaterialLogic.visibleSlots(0))
        assertEquals(listOf(30, 31), FusionMaterialLogic.visibleSlots(1))
        assertEquals(listOf(30, 31, 32), FusionMaterialLogic.visibleSlots(2))
        assertEquals((29..32).toList(), FusionMaterialLogic.visibleSlots(3))
        assertEquals((29..33).toList() + listOf(40), FusionMaterialLogic.visibleSlots(5))
        assertEquals((29..33).toList() + (39..40).toList(), FusionMaterialLogic.visibleSlots(6))
        assertEquals((29..33).toList() + (38..41).toList(), FusionMaterialLogic.visibleSlots(8))
        assertEquals((29..33).toList() + (38..41).toList(), FusionMaterialLogic.visibleSlots(9))
    }

    @Test
    fun `a material contributes when it supplies a final iv above the base`() {
        val base = intArrayOf(20, 20, 20, 20, 20, 20)
        val materials = listOf(
            intArrayOf(31, 20, 20, 20, 20, 20),
            intArrayOf(20, 30, 20, 20, 20, 20),
            intArrayOf(20, 20, 20, 20, 20, 20)
        )

        assertEquals(listOf(true, true, false), FusionMaterialLogic.contributions(base, materials))
    }

    @Test
    fun `tied final contributors glow but values at or below base do not`() {
        val base = intArrayOf(25, 25, 25, 25, 25, 25)
        val materials = listOf(
            intArrayOf(31, 25, 25, 25, 25, 25),
            intArrayOf(31, 24, 24, 24, 24, 24),
            intArrayOf(25, 25, 25, 25, 25, 25)
        )

        val result = FusionMaterialLogic.contributions(base, materials)
        assertTrue(result[0])
        assertTrue(result[1])
        assertFalse(result[2])
    }

    @Test
    fun `invalid stat widths are rejected`() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            FusionMaterialLogic.contributions(intArrayOf(1, 2), listOf(intArrayOf(1)))
        }
        assertTrue(error.message!!.contains("stat count"))
    }
}
