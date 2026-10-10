package jbro.cobblemon.policy.wild

import jbro.cobblemon.policy.config.PolicyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class WildRollsTest {
    private val ranges = PolicyConfig.DEFAULT_IV_RANGES

    @Test
    fun `alphas get two distinct perfect stats without changing the remaining rolls`() {
        val original = listOf(0, 5, 10, 15, 20, 30)
        val chosen = mutableSetOf<Int>()
        repeat(100) { seed ->
            val result = WildRolls.alphaIvs(original, Random(seed))
            assertEquals(2, result.count { it == 31 })
            result.indices.forEach { index ->
                if (result[index] == 31) chosen += index
                else assertEquals(original[index], result[index])
            }
            assertEquals(result, WildRolls.alphaIvs(result, Random(seed + 1)))
        }
        assertEquals(original.indices.toSet(), chosen)
    }

    @Test
    fun `one perfect stat needs only one upgrade and existing higher IV counts stay intact`() {
        val original = listOf(31, 0, 1, 2, 3, 4)
        val result = WildRolls.alphaIvs(original, Random(0))
        assertEquals(31, result[0])
        assertEquals(2, result.count { it == 31 })
        for (perfect in 2..6) {
            val ivs = List(6) { if (it < perfect) 31 else it }
            assertEquals(ivs, WildRolls.alphaIvs(ivs, Random(0)))
        }
    }

    @Test
    fun `iv bands follow their weights`() {
        assertEquals(0..9, ranges.pick(0.0))
        assertEquals(0..9, ranges.pick(19.999))
        assertEquals(10..19, ranges.pick(20.0))
        assertEquals(20..29, ranges.pick(65.0))
        assertEquals(30..31, ranges.pick(90.0))
        assertEquals(30..31, ranges.pick(99.999))
    }

    @Test
    fun `hidden ability hits one roll in the odds`() {
        assertEquals(1, (0 until 30).count { WildRolls.hiddenAbility(30, it) })
        assertFalse(WildRolls.hiddenAbility(0, 0))
        assertTrue(WildRolls.hiddenAbility(1, 0))
    }

    private fun List<jbro.cobblemon.policy.config.IvRange>.pick(point: Double) = WildRolls.ivRange(this, point).let { it.min..it.max }
}
