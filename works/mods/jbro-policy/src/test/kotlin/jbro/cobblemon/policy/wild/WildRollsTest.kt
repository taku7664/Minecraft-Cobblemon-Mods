package jbro.cobblemon.policy.wild

import jbro.cobblemon.policy.config.PolicyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildRollsTest {
    private val ranges = PolicyConfig.DEFAULT_IV_RANGES

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
    fun `hidden ability hits exactly the rate`() {
        assertEquals(30, (0 until 100).count { WildRolls.hiddenAbility(30, it) })
        assertFalse(WildRolls.hiddenAbility(0, 0))
        assertTrue(WildRolls.hiddenAbility(100, 99))
    }

    private fun List<jbro.cobblemon.policy.config.IvRange>.pick(point: Double) = WildRolls.ivRange(this, point).let { it.min..it.max }
}
